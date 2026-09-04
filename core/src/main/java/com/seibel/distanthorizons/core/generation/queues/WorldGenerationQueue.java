/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.render.renderer.AbstractDebugWireframeRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil.AssertFailureException;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.DhThreadFactory;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

public class WorldGenerationQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	/** Stable runtime capability marker for integrations that detect the fork's rejection backoff reflectively. */
	public static final boolean SUPPORTS_REJECTED_GENERATION_BACKOFF = true;

	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);
	private static final AbstractDebugWireframeRenderer DEBUG_RENDERER = SingletonInjector.INSTANCE.get(AbstractDebugWireframeRenderer.class);
	
	static final long INITIAL_REJECTION_BACKOFF_IN_MS = 1_000;
	static final long MAX_REJECTION_BACKOFF_IN_MS = 30_000;

	
	private final IDhApiWorldGenerator generator;
	private final IDhServerLevel level;
	
	/** contains the positions that need to be generated */
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> generationTasksByPos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> waitingTasks = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> inProgressGenTasksByLodPos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Long, DataSourceRetrievalTask> inProgressPriorityGenTasksByLodPos = new ConcurrentHashMap<>();
	private final GenerationRetryBackoffTracker generationRetryBackoffTracker = new GenerationRetryBackoffTracker();
	private int weightedGenerationStartIndex = 0;
	
	/** largest numerical detail level allowed */
	public final byte lowestDataDetail;
	/** smallest numerical detail level allowed */
	public final byte highestDataDetail;
	
	
	/** If not null this generator is in the process of shutting down */
	private volatile CompletableFuture<Void> generatorClosingFuture = null;
	
	/** 
	 * having a single thread queue for world gen can cause a bottleneck,
	 * however that's usually only for extremely fast custom generators
	 * and doesn't generally come up in normal gameplay.
	 */
	private final ScheduledExecutorService queueingThread = Executors.newSingleThreadScheduledExecutor(
		new DhThreadFactory("World Gen Queue", Thread.NORM_PRIORITY, false));
	private volatile boolean generationQueueRunning = false;
	private volatile QueueWorkerState queueWorkerState = QueueWorkerState.completed();
	private DhBlockPos2D generationTargetPos = DhBlockPos2D.ZERO;
		
	/** just used for rendering to the F3 menu */
	private int estimatedRemainingTaskCount = 0;
	private int estimatedRemainingChunkCount = 0;
	
	private final RollingAverage rollingAverageChunkGenTimeInMs = new RollingAverage(Runtime.getRuntime().availableProcessors() * 500);
	@Override public RollingAverage getRollingAverageChunkGenTimeInMs() { return this.rollingAverageChunkGenTimeInMs; }
	
	
	
	//=============//
	// constructor //
	//=============//
	///region constructor
	
	public WorldGenerationQueue(IDhApiWorldGenerator generator, IDhServerLevel level)
	{
		LOGGER.info("Creating world gen queue");
		this.generator = generator;
		this.level = level;
		this.lowestDataDetail = generator.getLargestDataDetailLevel();
		this.highestDataDetail = generator.getSmallestDataDetailLevel();
		
		DEBUG_RENDERER.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		LOGGER.info("Created world gen queue");
	}
	
	///endregion constructor
	
	
	
	//===============//
	// task handling //
	//===============//
	///region task handling
	
	@Override
	public long getPriorityRetrievalPos(long requestedPos)
	{
		return findPriorityRetrievalPos(
			requestedPos,
			this.lowestDataDetail,
			(pos, dataDetail) -> this.getRawGenerationAvailability(new DataSourceRetrievalTask(pos, dataDetail)));
	}

	static long findPriorityRetrievalPos(
		long requestedPos,
		byte lowestDataDetail,
		IPriorityAvailabilityResolver availabilityResolver)
	{
		byte requestedDataDetail = (byte) (DhSectionPos.getDetailLevel(requestedPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		for (int dataDetail = requestedDataDetail + 1; dataDetail <= lowestDataDetail; dataDetail++)
		{
			byte candidateDataDetail = (byte) dataDetail;
			long candidatePos = DhSectionPos.convertToDetailLevel(
				requestedPos,
				(byte) (candidateDataDetail + DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
			if (availabilityResolver.getAvailability(candidatePos, candidateDataDetail)
				== IDhApiWorldGenerator.GENERATION_PRIORITY)
			{
				return candidatePos;
			}
		}

		return requestedPos;
	}

	@FunctionalInterface
	interface IPriorityAvailabilityResolver
	{
		byte getAvailability(long pos, byte dataDetail);
	}

	@Override
	public CompletableFuture<DataSourceRetrievalResult> submitRetrievalTask(long pos, byte requiredDataDetail)
	{
		// the generator is shutting down, don't add new tasks
		if (this.generatorClosingFuture != null || this.queueingThread.isShutdown())
		{
			CompletableFuture<DataSourceRetrievalResult> f = new CompletableFuture<>();
			f.completeExceptionally(new CancellationException());
			return f;
		}
		
		// use the canonical task throughout waiting, generation, retry, and terminal completion
		// make sure the generator can provide the requested position
		if (requiredDataDetail < this.highestDataDetail)
		{
			throw new UnsupportedOperationException("Current generator does not meet requiredDataDetail level");
		}
		if (requiredDataDetail > this.lowestDataDetail)
		{
			requiredDataDetail = this.lowestDataDetail;
		}
		
		// the request should be at least chunk-sized
		LodUtil.assertTrue(DhSectionPos.getDetailLevel(pos) > requiredDataDetail + LodUtil.CHUNK_DETAIL_LEVEL);
		
		DataSourceRetrievalTask genTask = new DataSourceRetrievalTask(pos, requiredDataDetail);
		genTask.future.whenComplete((result, exception) -> this.removeTerminalTask(genTask));
		synchronized (this.generationTasksByPos)
		{
			if (this.generatorClosingFuture != null || this.queueingThread.isShutdown())
			{
				genTask.future.completeExceptionally(new CancellationException());
				return genTask.future;
			}

			DataSourceRetrievalTask canonicalTask = this.generationTasksByPos.get(pos);
			if (canonicalTask != null)
			{
				return canonicalTask.future;
			}

			this.generationTasksByPos.put(pos, genTask);
			this.waitingTasks.put(pos, genTask);
			if (genTask.future.isDone())
			{
				this.removeTerminalTask(genTask);
			}
		}
		return genTask.future;
	}
	
	@Override
	public void removeRetrievalRequestIf(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf)
	{
		for (DataSourceRetrievalTask task : this.generationTasksByPos.values())
		{
			if (removeIf.accept(task.pos))
			{
				task.cancel(true);
			}
		}
	}
	
	///endregion task handling
	
	
	
	//===============//
	// running tasks //
	//===============//
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos)
	{
		// update the target pos
		this.generationTargetPos = targetPos;
		
		// needs to be called at least once to start the queue
		this.tryQueueNewWorldGenRequestsAsync();
	}
	private void tryQueueNewWorldGenRequestsAsync()
	{
		synchronized (this)
		{
			if (this.generatorClosingFuture != null || this.queueingThread.isShutdown())
			{
				return;
			}

			if (!DhApiWorldProxy.INSTANCE.worldLoaded()
				|| DhApiWorldProxy.INSTANCE.tryGetReadOnly())
			{
				return;
			}
			if (this.generationQueueRunning)
			{
				return;
			}
			this.generationQueueRunning = true;
		}
		
		
		
		// queue world generation tasks on its own thread since this process is very slow and would lag the server thread
		QueueWorkerState currentQueueWorkerState = new QueueWorkerState();
		Runnable queueTask = () ->
		{
			if (!currentQueueWorkerState.tryStart())
			{
				this.generationQueueRunning = false;
				return;
			}

			try
			{
				this.generator.preGeneratorTaskStart();
			
				// queue generation tasks until the generator is full, or there are no more tasks to generate
				boolean taskStarted = true;
				while (!this.isGeneratorBusy()
						&& taskStarted)
				{
					taskStarted = this.tryStartNextWorldGenTask(this.generationTargetPos);
				}
			}
			catch (Exception e)
			{
				LOGGER.error("queueing exception: " + e.getMessage(), e);
			}
			finally
			{
				this.generationQueueRunning = false;
				currentQueueWorkerState.complete();
			}
		};
		synchronized (this.generationTasksByPos)
		{
			if (this.generatorClosingFuture != null || this.queueingThread.isShutdown())
			{
				this.generationQueueRunning = false;
				return;
			}
			this.queueWorkerState = currentQueueWorkerState;
			try
			{
				this.queueingThread.execute(queueTask);
			}
			catch (RejectedExecutionException e)
			{
				this.generationQueueRunning = false;
				currentQueueWorkerState.failBeforeStart(e);
			}
		}
	}
	private boolean isGeneratorBusy()
	{
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor == null)
		{
			// shouldn't happen, but just in case, don't queue more tasks
			return true;
		}
		
		// queue more tasks if any of the threads are available
		int worldGenThreadCount = Math.max(Config.Common.MultiThreading.numberOfThreads.get(), 1);
		return isGeneratorAtCapacity(this.inProgressGenTasksByLodPos.size(), worldGenThreadCount);
	}
	static boolean isGeneratorAtCapacity(int inProgressTaskCount, int threadCount)
	{
		return inProgressTaskCount >= Math.max(threadCount, 1);
	}
	/**
	 * @param targetPos the position to center the generation around
	 * @return false if no tasks were found to generate
	 */
	private boolean tryStartNextWorldGenTask(DhBlockPos2D targetPos)
	{
		if (this.waitingTasks.isEmpty())
		{
			return false;
		}
		
		
		int inProgressPriorityTaskCount = this.inProgressPriorityGenTasksByLodPos.size();
		int worldGenThreadCount = Math.max(Config.Common.MultiThreading.numberOfThreads.get(), 1);

		TaskSelection<DataSourceRetrievalTask> selectedTask = selectPreferredTask(
			this.waitingTasks.values(),
			this::getRunnableGenerationAvailability,
			task -> DhSectionPos.getCenterBlockPos(task.pos).chebyshevDist(targetPos),
			inProgressPriorityTaskCount,
			worldGenThreadCount,
			this.weightedGenerationStartIndex);
		
		if (selectedTask == null)
		{
			// no task is currently ready to run
			return false;
		}
		DataSourceRetrievalTask closestTask = selectedTask.task;
		
		synchronized (this.generationTasksByPos)
		{
			if (this.generatorClosingFuture != null || this.queueingThread.isShutdown())
			{
				return false;
			}

			// remove the task we found, we are going to start it and don't want to run it multiple times
			if (!this.waitingTasks.remove(closestTask.pos, closestTask))
			{
				return true;
			}
			if (closestTask.future.isCancelled())
			{
				this.clearRetryBackoffIfCanonical(closestTask);
				return true;
			}
			
			// do we need to modify this task to generate it?
			if (selectedTask.availability == IDhApiWorldGenerator.GENERATION_SPLIT)
			{
				this.clearRetryBackoffIfCanonical(closestTask);
				closestTask.future.complete(DataSourceRetrievalResult.CreateSplit());
			}
			else if (this.canGenerateDetailLevel(DhSectionPos.getDetailLevel(closestTask.pos)))
			{
				// detail level is correct for generation, start generation
				this.startWorldGenTaskGroup(
					closestTask,
					selectedTask.availability == IDhApiWorldGenerator.GENERATION_PRIORITY);
				if (worldGenThreadCount < 4)
				{
					this.weightedGenerationStartIndex = getNextWeightedStartIndex(this.weightedGenerationStartIndex);
				}
			}
			else
			{
				// detail level is too high (if the detail level was too low, the generator would've ignored the request),
				// split up the task
				this.clearRetryBackoffIfCanonical(closestTask);
				closestTask.future.complete(DataSourceRetrievalResult.CreateSplit());
			}
		}
		
		
		// a task has been started or queued,
		// queue another task
		return true;
	}
	private byte getRunnableGenerationAvailability(DataSourceRetrievalTask task)
	{
		if (this.generationRetryBackoffTracker.isBackingOff(task.pos, System.nanoTime()))
		{
			return IDhApiWorldGenerator.GENERATION_WAIT;
		}

		return this.getRawGenerationAvailability(task);
	}
	private byte getRawGenerationAvailability(DataSourceRetrievalTask task)
	{
		DhChunkPos chunkPosMin = new DhChunkPos(new DhBlockPos2D(DhSectionPos.getMinCornerBlockX(task.pos), DhSectionPos.getMinCornerBlockZ(task.pos)));
		byte availability = this.generator.getGenerationAvailability(
			chunkPosMin.getX(), chunkPosMin.getZ(), task.widthInChunks, task.requestDetailLevel);
		return availability == IDhApiWorldGenerator.GENERATION_SPLIT
			|| availability == IDhApiWorldGenerator.GENERATION_WAIT
			|| availability == IDhApiWorldGenerator.GENERATION_PRIORITY
			? availability
			: IDhApiWorldGenerator.GENERATION_READY;
	}
	static boolean shouldPreferTask(
		int aDistance, byte aAvailability,
		int bDistance, byte bAvailability,
		int inProgressPriorityTaskCount, int threadCount,
		int weightedStartIndex)
	{
		boolean aPriority = aAvailability == IDhApiWorldGenerator.GENERATION_PRIORITY;
		boolean bPriority = bAvailability == IDhApiWorldGenerator.GENERATION_PRIORITY;
		if (aPriority == bPriority)
		{
			return aDistance < bDistance;
		}

		boolean preferPriorityLane = shouldPreferPriorityLane(
			inProgressPriorityTaskCount,
			threadCount,
			weightedStartIndex);
		return preferPriorityLane == aPriority;
	}
	static <T> TaskSelection<T> selectPreferredTask(
		Iterable<T> tasks,
		ITaskAvailabilityResolver<T> availabilityResolver,
		ITaskDistanceResolver<T> distanceResolver,
		int inProgressPriorityTaskCount,
		int threadCount,
		int weightedStartIndex)
	{
		TaskSelection<T> selectedTask = null;
		for (T task : tasks)
		{
			byte availability = availabilityResolver.getAvailability(task);
			if (availability == IDhApiWorldGenerator.GENERATION_WAIT)
			{
				continue;
			}

			int distance = distanceResolver.getDistance(task);
			if (selectedTask == null
				|| shouldPreferTask(
					distance, availability,
					selectedTask.distance, selectedTask.availability,
					inProgressPriorityTaskCount, threadCount,
					weightedStartIndex))
			{
				selectedTask = new TaskSelection<>(task, distance, availability);
			}
		}

		return selectedTask;
	}
	static boolean shouldPreferPriorityLane(
		int inProgressPriorityTaskCount,
		int threadCount,
		int weightedStartIndex)
	{
		int normalizedThreadCount = Math.max(threadCount, 1);
		if (normalizedThreadCount < 4)
		{
			return Math.floorMod(weightedStartIndex, 4) < 3;
		}

		return inProgressPriorityTaskCount < getPriorityTaskLimit(normalizedThreadCount);
	}
	static int getNextWeightedStartIndex(int weightedStartIndex)
	{
		return (Math.floorMod(weightedStartIndex, 4) + 1) % 4;
	}
	static int getPriorityTaskLimit(int threadCount)
	{
		int normalizedThreadCount = Math.max(threadCount, 1);
		return (int) (((long) normalizedThreadCount * 3L) / 4L);
	}
	private boolean canGenerateDetailLevel(byte taskDetailLevel)
	{
		byte requestedDetailLevel = (byte) (taskDetailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		return (this.highestDataDetail <= requestedDetailLevel && requestedDetailLevel <= this.lowestDataDetail);
	}
	private void startWorldGenTaskGroup(DataSourceRetrievalTask worldGenTask, boolean priorityTask)
	{
		long taskPos = worldGenTask.pos;
		LodUtil.assertTrue(
			worldGenTask.requestDetailLevel >= this.highestDataDetail 
			&& worldGenTask.requestDetailLevel <= this.lowestDataDetail,
			"World gen task started that isn't within the range that the generator can create.");
		
		long generationStartMsTime = System.currentTimeMillis();
		CompletableFuture<FullDataSourceV2> generationFuture;
		try
		{
			generationFuture = this.startGenerationEvent(worldGenTask, priorityTask);
		}
		catch (RejectedExecutionException e)
		{
			boolean requeued = this.tryRequeueRejectedTask(worldGenTask);
			this.removeInProgressTask(worldGenTask);
			if (!requeued)
			{
				this.clearRetryBackoffIfCanonical(worldGenTask);
				worldGenTask.future.completeExceptionally(e);
			}
			return;
		}
		catch (RuntimeException e)
		{
			this.removeInProgressTask(worldGenTask);
			this.clearRetryBackoffIfCanonical(worldGenTask);
			worldGenTask.future.completeExceptionally(e);
			LOGGER.error("Error starting data generation for pos: " + DhSectionPos.toString(taskPos), e);
			return;
		}
		worldGenTask.attachGenerationFuture(generationFuture);
		
		// calculate generation speed
		generationFuture.thenRun(() -> 
		{
			long totalGenTimeInMs = System.currentTimeMillis() - generationStartMsTime;
			int chunkCount = worldGenTask.widthInChunks * worldGenTask.widthInChunks;
			double timePerChunk = (double)totalGenTimeInMs / (double)chunkCount;
			this.rollingAverageChunkGenTimeInMs.add(timePerChunk);
		});
		
		generationFuture.handle((FullDataSourceV2 fullDataSource, Throwable exception) ->
		{
			try
			{
				if (exception != null)
				{
					if (isRetryableGenerationRejection(
						exception,
						this.generatorClosingFuture != null || this.queueingThread.isShutdown(),
						worldGenTask.future.isCancelled())
						&& this.tryRequeueRejectedTask(worldGenTask))
					{
						return null;
					}
					this.clearRetryBackoffIfCanonical(worldGenTask);

					// don't log the shutdown exceptions
					if (!ExceptionUtil.isInterruptOrReject(exception))
					{
						LOGGER.error("Error generating data for pos: " + DhSectionPos.toString(taskPos), exception);
					}
					
					LodUtil.assertTrue(fullDataSource == null);
					worldGenTask.future.completeExceptionally(exception);
				}
				else
				{
					this.clearRetryBackoffIfCanonical(worldGenTask);
					boolean accepted = worldGenTask.future.complete(DataSourceRetrievalResult.CreateSuccess(taskPos, fullDataSource));
					if (!accepted && fullDataSource != null)
					{
						fullDataSource.close();
					}
				}
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error completing world gen task at pos: ["+DhSectionPos.toString(taskPos)+"].", e);
				worldGenTask.future.completeExceptionally(e);
			}
			finally
			{
				this.removeInProgressTask(worldGenTask);
				this.tryQueueNewWorldGenRequestsAsync();
			}
			
			return null;
		});
	}
	private CompletableFuture<FullDataSourceV2> startGenerationEvent(DataSourceRetrievalTask task, boolean priorityTask)
	{
		this.inProgressGenTasksByLodPos.put(task.pos, task);
		if (priorityTask)
		{
			this.inProgressPriorityGenTasksByLodPos.put(task.pos, task);
		}
		
		DhChunkPos chunkPosMin = new DhChunkPos(new DhBlockPos2D(DhSectionPos.getMinCornerBlockX(task.pos), DhSectionPos.getMinCornerBlockZ(task.pos)));
		
		EDhApiDistantGeneratorMode generatorMode = Config.Common.WorldGenerator.distantGeneratorMode.get();
		EDhApiWorldGeneratorReturnType returnType = this.generator.getReturnType();
		switch (returnType) 
		{
			case VANILLA_CHUNKS: 
			{	
				return this.startVanillaChunkGenerationEvent(task, chunkPosMin, generatorMode);
			}
			case API_CHUNKS: 
			{
				return this.startApiChunkGenerationEvent(task, chunkPosMin, generatorMode);
			}
			case API_DATA_SOURCES:
			{
				return this.startApiDataSourceGenerationEvent(task, chunkPosMin, generatorMode);
			}
			default: 
			{
				Config.Common.WorldGenerator.enableDistantGeneration.set(false);
				throw new AssertFailureException("Unknown return type: " + returnType);
			}
		}
	}
	private void removeInProgressTask(DataSourceRetrievalTask task)
	{
		this.inProgressGenTasksByLodPos.remove(task.pos, task);
		this.inProgressPriorityGenTasksByLodPos.remove(task.pos, task);
	}
	private void removeTerminalTask(DataSourceRetrievalTask task)
	{
		if (!task.future.isDone())
		{
			return;
		}

		synchronized (this.generationTasksByPos)
		{
			this.waitingTasks.remove(task.pos, task);
			this.removeInProgressTask(task);
			if (this.generationTasksByPos.remove(task.pos, task))
			{
				this.generationRetryBackoffTracker.clear(task.pos);
			}
		}
	}
	private boolean tryRequeueRejectedTask(DataSourceRetrievalTask task)
	{
		synchronized (this.generationTasksByPos)
		{
			if (this.generatorClosingFuture != null
				|| this.queueingThread.isShutdown()
				|| task.future.isCancelled()
				|| this.generationTasksByPos.get(task.pos) != task)
			{
				return false;
			}

			this.removeInProgressTask(task);
			long retryDelayInMs = this.generationRetryBackoffTracker.recordFailure(task.pos, System.nanoTime());
			this.waitingTasks.put(task.pos, task);

			try
			{
				this.queueingThread.schedule(
					this::tryQueueNewWorldGenRequestsAsync,
					retryDelayInMs,
					TimeUnit.MILLISECONDS);
				return true;
			}
			catch (RejectedExecutionException e)
			{
				this.waitingTasks.remove(task.pos, task);
				this.generationRetryBackoffTracker.clear(task.pos);
				return false;
			}
		}
	}
	private void clearRetryBackoffIfCanonical(DataSourceRetrievalTask task)
	{
		synchronized (this.generationTasksByPos)
		{
			if (this.generationTasksByPos.get(task.pos) == task)
			{
				this.generationRetryBackoffTracker.clear(task.pos);
			}
		}
	}
	static boolean isRetryableGenerationRejection(Throwable exception, boolean queueClosing, boolean taskCancelled)
	{
		return !queueClosing
			&& !taskCancelled
			&& ExceptionUtil.ensureUnwrap(exception) instanceof RejectedExecutionException;
	}
	private CompletableFuture<FullDataSourceV2> startVanillaChunkGenerationEvent(
		DataSourceRetrievalTask task, DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		ArrayList<IChunkWrapper> generatedChunks = new ArrayList<>(task.widthInChunks * task.widthInChunks);
		
		CompletableFuture<Void> chunkGenFuture = this.generator.generateChunks(
			chunkPosMin.getX(), chunkPosMin.getZ(),
			task.widthInChunks,
			task.requestDetailLevel,
			generatorMode,
			ThreadPoolUtil.getWorldGenExecutor(),
			(Object[] generatedObjectArray) ->
			{
				try
				{
					IChunkWrapper chunkWrapper = WRAPPER_FACTORY.createChunkWrapper(generatedObjectArray);
					generatedChunks.add(chunkWrapper);
				}
				catch (ClassCastException e)
				{
					LOGGER.error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.enableDistantGeneration.set(false);
				}
				catch (Exception e)
				{
					LOGGER.error("Unexpected world generator error. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.enableDistantGeneration.set(false);
				}
			}
		);
		returnFuture.whenComplete((ignored, error) ->
		{
			if (returnFuture.isCancelled())
			{
				chunkGenFuture.cancel(true);
			}
		});
		
		chunkGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			return null;
		});
		chunkGenFuture.thenRun(() ->
		{
			FullDataSourceV2 requestedDataSource = FullDataSourceV2.createEmpty(task.pos);
			
			// process chunks //
			for (int i = 0; i < generatedChunks.size(); i++)
			{
				IChunkWrapper chunkWrapper = generatedChunks.get(i);
				
				// only light the chunk here if necessary,
				// lighting before this point is preferred but for legacy API use this
				// check should be done
				if (!chunkWrapper.isDhBlockLightingCorrect())
				{
					ArrayList<IChunkWrapper> nearbyChunkList = new ArrayList<>();
					nearbyChunkList.add(chunkWrapper);
					byte maxSkyLight = this.level.getLevelWrapper().hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT;
					DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, nearbyChunkList, maxSkyLight);
				}
				
				try (FullDataSourceV2 generatedDataSource = LodDataBuilder.createFromChunk(this.level.getLevelWrapper(), chunkWrapper))
				{
					LodUtil.assertTrue(generatedDataSource != null);
					requestedDataSource.updateFromDataSource(generatedDataSource);
				}
			}
			
			DhLightingEngine.INSTANCE.bakeDataSourceSkyLight(requestedDataSource, LodUtil.MAX_MC_LIGHT);
			returnFuture.complete(requestedDataSource);
		});
		
		return returnFuture;
	}
	private CompletableFuture<FullDataSourceV2> startApiChunkGenerationEvent(
		DataSourceRetrievalTask task,  DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		ArrayList<DhApiChunk> generatedChunks = new ArrayList<>(task.widthInChunks * task.widthInChunks);
		
		CompletableFuture<Void> chunkGenFuture = this.generator.generateApiChunks(
			chunkPosMin.getX(), chunkPosMin.getZ(),
			task.widthInChunks,
			task.requestDetailLevel,
			generatorMode,
			ThreadPoolUtil.getWorldGenExecutor(),
			(DhApiChunk apiChunk) -> { generatedChunks.add(apiChunk); }
		);
		returnFuture.whenComplete((ignored, error) ->
		{
			if (returnFuture.isCancelled())
			{
				chunkGenFuture.cancel(true);
			}
		});
		
		
		chunkGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			return null;
		});
		chunkGenFuture.thenRun(() ->
		{
			FullDataSourceV2 requestedDataSource = FullDataSourceV2.createEmpty(task.pos);
			
			for (int i = 0; i < generatedChunks.size(); i++)
			{
				DhApiChunk apiChunk = generatedChunks.get(i);
				
				try(FullDataSourceV2 generatedDataSource = LodDataBuilder.createFromApiChunkData(apiChunk, this.generator.runApiValidation()))
				{
					requestedDataSource.updateFromDataSource(generatedDataSource);
				}
				catch (DataCorruptedException | IllegalArgumentException e)
				{
					LOGGER.error("World generator returned a corrupt API chunk. Error: [" + e.getMessage() + "]. World generator disabled.", e);
					Config.Common.WorldGenerator.enableDistantGeneration.set(false);
				}
			}
			
			returnFuture.complete(requestedDataSource);
		});
		
		return returnFuture;
	}
	private CompletableFuture<FullDataSourceV2> startApiDataSourceGenerationEvent(
		DataSourceRetrievalTask task, DhChunkPos chunkPosMin, EDhApiDistantGeneratorMode generatorMode)
	{
		final CompletableFuture<FullDataSourceV2> returnFuture = new CompletableFuture<>();
		
		
		// done to reduce GC overhead
		FullDataSourceV2 pooledDataSource = FullDataSourceV2.createEmpty(task.pos);
		CompletableFuture<Void> lodGenFuture;
		try
		{
			// set here so the API user doesn't have to pass in this value anywhere themselves
			pooledDataSource.setRunApiSetterValidation(this.generator.runApiValidation());

			// only apply to children if we aren't at the bottom of the tree
			pooledDataSource.applyToChildren = DhSectionPos.getDetailLevel(pooledDataSource.getPos()) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;
			// apply to parents up to the top of the tree
			pooledDataSource.applyToParent = DhSectionPos.getDetailLevel(pooledDataSource.getPos()) < FullDataSourceProviderV2.ROOT_SECTION_DETAIL_LEVEL;

			lodGenFuture = Objects.requireNonNull(this.generator.generateLod(
				chunkPosMin.getX(), chunkPosMin.getZ(),
				DhSectionPos.getX(task.pos), DhSectionPos.getZ(task.pos),
				(byte) (DhSectionPos.getDetailLevel(task.pos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL),
				pooledDataSource,
				generatorMode,
				ThreadPoolUtil.getWorldGenExecutor(),
				(IDhApiFullDataSource apiDataSource) -> { }
			), "lodGenFuture");
		}
		catch (RuntimeException e)
		{
			pooledDataSource.close();
			throw e;
		}
		returnFuture.whenComplete((ignored, error) ->
		{
			if (returnFuture.isCancelled())
			{
				lodGenFuture.cancel(true);
			}
		});
		
		
		lodGenFuture.exceptionally((throwable) ->
		{
			returnFuture.completeExceptionally(throwable);
			pooledDataSource.close();
			return null;
		});
		lodGenFuture.thenRun(() ->
		{
			returnFuture.complete(pooledDataSource);
		});
		
		return returnFuture;
	}
	
	
	
	//===================//
	// getters / setters //
	//===================//
	///region getters/setters
	
	@Override public int getWaitingTaskCount() { return this.waitingTasks.size(); }
	@Override public int getInProgressTaskCount() { return this.inProgressGenTasksByLodPos.size(); }
	
	@Override public byte lowestDataDetail() { return this.lowestDataDetail; }
	@Override public byte highestDataDetail() { return this.highestDataDetail; }
	
	@Override public String getRetrievalTypeName() { return "generating chunks"; }
	
	@Override public int getEstimatedRemainingTaskCount() { return this.estimatedRemainingTaskCount; }
	@Override public void setEstimatedRemainingTaskCount(int newEstimate) { this.estimatedRemainingTaskCount = newEstimate; }
	
	@Override public int getRetrievalEstimatedRemainingChunkCount() { return this.estimatedRemainingChunkCount; }
	@Override public void setRetrievalEstimatedRemainingChunkCount(int newEstimate) { this.estimatedRemainingChunkCount = newEstimate; }
	
	@Override 
	public void addDebugMenuStringsToList(List<String> messageList)
	{
		int threadCount = Math.max(Config.Common.MultiThreading.numberOfThreads.get(), 1);
		String priorityQuota = threadCount < 4
			? "3:1 starts"
			: Integer.toString(getPriorityTaskLimit(threadCount));
		messageList.add(
			"World Gen Queue: waiting " + this.waitingTasks.size()
				+ ", in progress " + this.inProgressGenTasksByLodPos.size() + "/" + threadCount
				+ ", priority " + this.inProgressPriorityGenTasksByLodPos.size() + " (quota " + priorityQuota + ")"
				+ ", rejection backoff positions "
				+ this.generationRetryBackoffTracker.getBackingOffPositionCount(System.nanoTime()));
	}
	
	@Override
	public int getQueuedChunkCount()
	{
		int chunkCount = 0;
		for (long pos : this.waitingTasks.keySet())
		{
			int chunkWidth = DhSectionPos.getBlockWidth(pos) / LodUtil.CHUNK_WIDTH;
			chunkCount += (chunkWidth * chunkWidth);
		}
		
		return chunkCount;
	}
	
	///endregion getters/setters
	
	
	
	//=======//
	// debug //
	//=======//
	///region debug
	
	@Override
	public void debugRender(AbstractDebugWireframeRenderer renderer)
	{
		int levelMinY = this.level.getLevelWrapper().getMinHeight();
		int levelMaxY = this.level.getLevelWrapper().getMaxHeight();
		
		// show the wireframe a bit lower than world max height,
		// since most worlds don't render all the way up to the max height
		int levelHeightRange = (levelMaxY - levelMinY);
		int maxY = levelMaxY - (levelHeightRange / 2);
		
		
		// blue - queued
		this.waitingTasks.keySet().forEach((Long pos) -> 
		{ 
			renderer.renderBox(
				new AbstractDebugWireframeRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.blue)
			); 
		});
		
		// red - in progress
		this.inProgressGenTasksByLodPos.forEach((Long pos, DataSourceRetrievalTask task) -> 
		{ 
			renderer.renderBox(
				new AbstractDebugWireframeRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.red)
			); 
		});
	}
	
	///endregion debug
	
	
	
	//==========//
	// shutdown //
	//==========//
	///region shutdown
	
	@Override
	public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		final CompletableFuture<Void> closingFuture;
		final CompletableFuture<Void> queueWorkerStoppedFuture;
		synchronized (this.generationTasksByPos)
		{
			if (this.generatorClosingFuture != null)
			{
				return this.generatorClosingFuture;
			}

			LOGGER.info("Closing world gen queue");
			closingFuture = new CompletableFuture<>();
			this.generatorClosingFuture = closingFuture;
			this.queueingThread.shutdownNow();
			QueueWorkerState currentQueueWorkerState = this.queueWorkerState;
			if (currentQueueWorkerState.cancelBeforeStart())
			{
				this.generationQueueRunning = false;
			}
			queueWorkerStoppedFuture = currentQueueWorkerState.completionFuture;
		}
		
		
		// stop and remove any in progress tasks
		ArrayList<CompletableFuture<Void>> inProgressTasksCancelingFutures = new ArrayList<>(this.inProgressGenTasksByLodPos.size());
		this.inProgressGenTasksByLodPos.values().forEach((DataSourceRetrievalTask genTask) ->
		{
			CompletableFuture<DataSourceRetrievalResult> genFuture = genTask.future;
			
			if (cancelCurrentGeneration)
			{
				genTask.cancel(alsoInterruptRunning);
			}
			
			inProgressTasksCancelingFutures.add(genFuture.handle((DataSourceRetrievalResult result, Throwable throwable) ->
			{
				if (throwable instanceof CompletionException)
				{
					throwable = throwable.getCause();
				}
				
				if (throwable != null
					&& !UncheckedInterruptedException.isInterrupt(throwable)
					&& !(throwable instanceof CancellationException))
				{
					LOGGER.error("Error when terminating data generation for pos: ["+DhSectionPos.toString(genTask.pos)+"], error: ["+throwable.getMessage()+"].", throwable);
				}
				
				if (result != null 
					&& result.dataSource != null)
				{
					result.dataSource.close();
				}
				
				return null;
			}));
		});
		inProgressTasksCancelingFutures.add(queueWorkerStoppedFuture);
		CompletableFuture.allOf(inProgressTasksCancelingFutures.toArray(new CompletableFuture[0]))
			.whenComplete((ignored, exception) ->
			{
				if (exception == null)
				{
					closingFuture.complete(null);
				}
				else
				{
					closingFuture.completeExceptionally(exception);
				}
			});
		
		return closingFuture;
	}
	
	@Override
	public void close()
	{
		LOGGER.info("Closing " + WorldGenerationQueue.class.getSimpleName() + "...");
		
		if (this.generatorClosingFuture == null)
		{
			this.startClosingAsync(true, true);
		}
		LodUtil.assertTrue(this.generatorClosingFuture != null);
		
		
		LOGGER.info("Shutting down world generator thread pool...");
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor != null)
		{
			int queueSize = executor.getQueueSize();
			executor.clearQueue();
			LOGGER.info("World generator thread pool shutdown with [" + queueSize + "] incomplete tasks.");
		}
		
		this.generationTasksByPos.values().forEach((worldGenTask) -> worldGenTask.cancel(true));
		this.generationRetryBackoffTracker.clear();
		
		
		this.generator.close();
		DEBUG_RENDERER.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		
		
		try
		{
			this.generatorClosingFuture.cancel(true);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Failed to close generation queue: ", e);
		}
		
		
		LOGGER.info("Finished closing " + WorldGenerationQueue.class.getSimpleName());
	}
	
	///endregion shutdown
	
	
	
	//================//
	// helper classes //
	//================//
	///region helper classes
	
	@FunctionalInterface
	interface ITaskAvailabilityResolver<T>
	{
		byte getAvailability(T task);
	}

	@FunctionalInterface
	interface ITaskDistanceResolver<T>
	{
		int getDistance(T task);
	}

	static class QueueWorkerState
	{
		private static final int QUEUED = 0;
		private static final int RUNNING = 1;
		private static final int TERMINAL = 2;

		public final CompletableFuture<Void> completionFuture = new CompletableFuture<>();
		private int state = QUEUED;

		public static QueueWorkerState completed()
		{
			QueueWorkerState state = new QueueWorkerState();
			state.cancelBeforeStart();
			return state;
		}

		public synchronized boolean tryStart()
		{
			if (this.state != QUEUED)
			{
				return false;
			}
			this.state = RUNNING;
			return true;
		}

		public boolean cancelBeforeStart()
		{
			synchronized (this)
			{
				if (this.state != QUEUED)
				{
					return false;
				}
				this.state = TERMINAL;
			}
			this.completionFuture.complete(null);
			return true;
		}

		public void failBeforeStart(Throwable exception)
		{
			synchronized (this)
			{
				if (this.state != QUEUED)
				{
					return;
				}
				this.state = TERMINAL;
			}
			this.completionFuture.completeExceptionally(exception);
		}

		public void complete()
		{
			synchronized (this)
			{
				if (this.state == TERMINAL)
				{
					return;
				}
				this.state = TERMINAL;
			}
			this.completionFuture.complete(null);
		}
	}

	static class TaskSelection<T>
	{
		public final T task;
		public final int distance;
		public final byte availability;
		
		public TaskSelection(T task, int distance, byte availability)
		{
			this.task = task;
			this.distance = distance;
			this.availability = availability;
		}
	}

	static class GenerationRetryBackoffTracker
	{
		private final ConcurrentHashMap<Long, GenerationRetryBackoff> retryBackoffByPos = new ConcurrentHashMap<>();

		public long recordFailure(long pos, long currentTimeNanos)
		{
			GenerationRetryBackoff backoff = this.retryBackoffByPos.compute(pos, (ignoredPos, previousBackoff) ->
			{
				int failureCount = previousBackoff == null
					? 1
					: previousBackoff.failureCount == Integer.MAX_VALUE
						? Integer.MAX_VALUE
						: previousBackoff.failureCount + 1;
				long delayInMs = calculateRejectionBackoffInMs(failureCount);
				return new GenerationRetryBackoff(
					failureCount,
					currentTimeNanos + TimeUnit.MILLISECONDS.toNanos(delayInMs));
			});

			return calculateRejectionBackoffInMs(backoff.failureCount);
		}

		public boolean isBackingOff(long pos, long currentTimeNanos)
		{
			GenerationRetryBackoff backoff = this.retryBackoffByPos.get(pos);
			return backoff != null && currentTimeNanos - backoff.retryNotBeforeNanos < 0;
		}

		public int getBackingOffPositionCount(long currentTimeNanos)
		{
			int backingOffPositionCount = 0;
			for (GenerationRetryBackoff backoff : this.retryBackoffByPos.values())
			{
				if (currentTimeNanos - backoff.retryNotBeforeNanos < 0)
				{
					backingOffPositionCount++;
				}
			}
			return backingOffPositionCount;
		}

		public void clear(long pos) { this.retryBackoffByPos.remove(pos); }
		public void clear() { this.retryBackoffByPos.clear(); }
	}

	private static class GenerationRetryBackoff
	{
		public final int failureCount;
		public final long retryNotBeforeNanos;

		public GenerationRetryBackoff(int failureCount, long retryNotBeforeNanos)
		{
			this.failureCount = failureCount;
			this.retryNotBeforeNanos = retryNotBeforeNanos;
		}
	}

	static long calculateRejectionBackoffInMs(int failureCount)
	{
		int exponent = Math.min(Math.max(failureCount - 1, 0), 30);
		long delayInMs = INITIAL_REJECTION_BACKOFF_IN_MS << exponent;
		return Math.min(delayInMs, MAX_REJECTION_BACKOFF_IN_MS);
	}

	///endregion helper classes
	
	
	
}
