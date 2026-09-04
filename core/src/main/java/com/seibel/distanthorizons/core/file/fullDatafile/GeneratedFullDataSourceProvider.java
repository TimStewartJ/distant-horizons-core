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

package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.file.fullDatafile.V2.FullDataSourceProviderV2;
import com.seibel.distanthorizons.core.util.delayedSaveCache.DelayedDataSourceSaveCache;
import com.seibel.distanthorizons.core.file.structure.ISaveStructure;
import com.seibel.distanthorizons.core.generation.queues.IFullDataSourceRetrievalQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.ERetrievalResultState;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.util.objects.pooling.PhantomArrayList.PhantomArrayListPool;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongPredicate;
import java.util.stream.IntStream;

public class GeneratedFullDataSourceProvider extends FullDataSourceProviderV2 implements IDebugRenderable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();;
	
	/** 
	 * Having this number too high means we may end up
	 * queueing tasks in the wrong location if the
	 * player is constantly teleporting/moving.
	 */
	public static final int MAX_RETRIEVAL_REQUESTS_PER_THREAD = 20;
	
	public static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("Generated Provider");
	
	
	public final AtomicReference<IFullDataSourceRetrievalQueue> worldGenQueueRef = new AtomicReference<>(null);
	private final ArrayList<IOnWorldGenCompleteListener> onWorldGenTaskCompleteListeners = new ArrayList<>();
	
	protected final DelayedDataSourceSaveCache delayedFullDataSourceSaveCache = new DelayedDataSourceSaveCache(this::onDataSourceSaveAsync, 10_000);
	
	private final ConcurrentHashMap<Long, CompletableFuture<DataSourceRetrievalResult>> queuedRetrievalFutureByPos = new ConcurrentHashMap<>();
	
	
	
	//=============//
	// constructor //
	//=============//
	//region constructor
	
	public GeneratedFullDataSourceProvider(IDhLevel level, ISaveStructure saveStructure) throws SQLException, IOException 
	{ this(level, saveStructure, null); }
	public GeneratedFullDataSourceProvider(IDhLevel level, ISaveStructure saveStructure, @Nullable File saveDirOverride) throws SQLException, IOException
	{ super(level, saveStructure, saveDirOverride); }
	
	//endregion constructor
	
	
	
	//=================//
	// event listeners //
	//=================//
	//region event listeners
	
	public void addWorldGenCompleteListener(IOnWorldGenCompleteListener listener) 
	{
		synchronized (this.onWorldGenTaskCompleteListeners)
		{
			this.onWorldGenTaskCompleteListeners.add(listener);
		}
	}
	public void removeWorldGenCompleteListener(IOnWorldGenCompleteListener listener) 
	{
		synchronized (this.onWorldGenTaskCompleteListeners)
		{
			this.onWorldGenTaskCompleteListeners.remove(listener);
		}
	}
	
	//endregion event listeners
	
	
	
	//========//
	// events //
	//========//
	//region events
	
	private void onWorldGenTaskComplete(
		@NotNull Long genPos,
		@NotNull CompletableFuture<DataSourceRetrievalResult> completedFuture,
		@Nullable DataSourceRetrievalResult genTaskResult,
		@Nullable Throwable exception)
	{
		try
		{
			if (genTaskResult != null
				&& genTaskResult.dataSource != null)
			{
				genTaskResult.dataSource.recordLastSeen();
			}
			
			if (exception != null)
			{
				// don't log shutdown exceptions
				if (!ExceptionUtil.isInterruptOrReject(exception))
				{
					LOGGER.error("Uncaught Gen Task Exception at [" + DhSectionPos.toString(genPos) + "], error: [" + exception.getMessage() + "].", exception);
				}
				return;
			}
			
			Objects.requireNonNull(genTaskResult);
			if (genTaskResult.state == ERetrievalResultState.SUCCESS)
			{
				LodUtil.assertTrue(genTaskResult.dataSource != null, "Successful retrieval object should have a datasource.");
				
				this.dataUpdater.updateDataSource(genTaskResult.dataSource);
				
				// synchronized to prevent a rare issue where the world generator is being shut down while this listener is firing
				synchronized (this.onWorldGenTaskCompleteListeners)
				{
					// fire the event listeners 
					for (IOnWorldGenCompleteListener listener : this.onWorldGenTaskCompleteListeners)
					{
						listener.onWorldGenTaskComplete(genTaskResult.pos);
					}
				}
				
				genTaskResult.dataSource.close();
			}
			else if (genTaskResult.state == ERetrievalResultState.REQUIRES_SPLITTING)
			{
				// task was split
				LodUtil.assertTrue(genTaskResult.dataSource == null, "Split retrieval object should not have a datasource.");
			}
			else
			{
				// shouldn't happen, but just in case
				LOGGER.warn("Unexpected gen Task state at: [" + DhSectionPos.toString(genTaskResult.pos) + "], state: [" + genTaskResult.state + "], datasource: NULL, exception: NULL.");
			}
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected issue during onWorldGenTaskComplete, error: ["+e.getMessage()+"].", e);
		}
		finally
		{
			this.queuedRetrievalFutureByPos.remove(genPos, completedFuture);
		}
	}
	
	//endregion events
	
	
	
	//===================================//
	// world gen (data source retrieval) //
	//===================================//
	//region world gen
	
	/** @see IFullDataSourceRetrievalQueue#lowestDataDetail() */
	public byte lowestDataDetailLevel()
	{
		IFullDataSourceRetrievalQueue fullDataSourceRetrievalQueue = this.worldGenQueueRef.get();
		if (fullDataSourceRetrievalQueue == null)
		{
			return DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL;
		}
		
		return (byte) (DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL + fullDataSourceRetrievalQueue.lowestDataDetail());
	}
	
	public static int getMaxRetrievalQueueCount() 
	{ return MAX_RETRIEVAL_REQUESTS_PER_THREAD * Config.Common.MultiThreading.numberOfThreads.get(); }
	
	
	
	/**
	 * Assigns the queue for handling world gen and does first time setup as well. <br> 
	 * Assumes there isn't a pre-existing queue. 
	 */
	public void setWorldGenerationQueue(IFullDataSourceRetrievalQueue newWorldGenQueue)
	{
		boolean oldQueueExists = this.worldGenQueueRef.compareAndSet(null, newWorldGenQueue);
		LodUtil.assertTrue(oldQueueExists, "previous world gen queue is still here!");
		LOGGER.info("Set world gen queue for level [" + this.levelId + "].");
	}
	
	@Override
	public boolean canRetrieveMissingDataSources() { return true; }
	
	@Override
	public void setEstimatedRemainingRetrievalChunkCount(long newCount) 
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.setRetrievalEstimatedRemainingChunkCount(newCount);
		}
	}
	
	@Override
	public void setCanRegenerate(boolean canRegen) 
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.setCanRegenerate(canRegen);
		}
	}
	
	@Override
	public boolean canQueueRetrievalNow() { return this.canQueueRetrievalNow(false); }
	public boolean canQueueRetrievalNow(boolean pruneWaitingTasksAboveLimit)
	{
		if (!super.canQueueRetrievalNow())
		{
			return false;
		}
		
		
		// we can't queue anything if the world generator isn't set up yet
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return false;
		}
		
		
		
		int maxWorldGenQueueCount = getMaxRetrievalQueueCount();
		int currentQueueCount = WorldChunkUpdateManager.INSTANCE.getTotalQueuedCount();
		
		
		
		// don't queue additional world gen requests if there are
		// a lot of chunks waiting to update
		if (currentQueueCount >= maxWorldGenQueueCount)
		{
			return false;
		}
		
		
		if (this.delayedFullDataSourceSaveCache.getUnsavedCount() >= maxWorldGenQueueCount)
		{
			// don't queue additional world gen requests if there are
			// a lot of data sources in memory 
			// (this is done to prevent infinite memory growth)
			
			// clear out the data sources that are in memory so
			// we can start queuing new world gen tasks
			this.delayedFullDataSourceSaveCache.flush();
		}
		
		
		int availableTaskSlots = maxWorldGenQueueCount - worldGenQueue.getWaitingTaskCount();
		if (availableTaskSlots == 0)
		{
			return false;
		}
		else if (availableTaskSlots < 0)
		{
			if (pruneWaitingTasksAboveLimit)
			{
				AtomicInteger tasksToCancel = new AtomicInteger(availableTaskSlots * -1);
				worldGenQueue.removeRetrievalRequestIf(taskPos -> tasksToCancel.getAndDecrement() > 0);
			}
			else
			{
				// don't queue additional world gen requests beyond the max allotted count
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public CompletableFuture<DataSourceRetrievalResult> queuePositionForRetrieval(Long genPos)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return null;
		}
		
		long priorityPos = worldGenQueue.getPriorityRetrievalPos(genPos);
		if (shouldQueuePriorityRetrieval(
			genPos,
			priorityPos,
			this.queuedRetrievalFutureByPos::containsKey,
			this::hasUsableGeneratedCoverage))
		{
			this.queueSinglePositionForRetrieval(worldGenQueue, priorityPos);
		}
		
		return this.queueSinglePositionForRetrieval(worldGenQueue, genPos);
	}
	
	private CompletableFuture<DataSourceRetrievalResult> queueSinglePositionForRetrieval(
		IFullDataSourceRetrievalQueue worldGenQueue,
		long genPos)
	{
		synchronized (this.queuedRetrievalFutureByPos)
		{
			CompletableFuture<DataSourceRetrievalResult> existingFuture =
				this.queuedRetrievalFutureByPos.get(genPos);
			if (existingFuture != null)
			{
				return existingFuture;
			}
			
			CompletableFuture<DataSourceRetrievalResult> worldGenFuture = worldGenQueue.submitRetrievalTask(
				genPos,
				(byte) (DhSectionPos.getDetailLevel(genPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
			this.queuedRetrievalFutureByPos.put(genPos, worldGenFuture);
			worldGenFuture.whenComplete((result, exception) ->
				this.onWorldGenTaskComplete(genPos, worldGenFuture, result, exception));
			return worldGenFuture;
		}
	}
	
	static boolean shouldQueuePriorityRetrieval(
		long requestedPos,
		long priorityPos,
		LongPredicate isQueued,
		LongPredicate hasUsableGeneratedCoverage)
	{
		return priorityPos != requestedPos
			&& !isQueued.test(priorityPos)
			&& !hasUsableGeneratedCoverage.test(priorityPos);
	}
	
	@Override
	public void removeRetrievalRequestIf(DhSectionPos.IPrimitiveLongConsumer removeIf)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue != null)
		{
			worldGenQueue.removeRetrievalRequestIf(removeIf);
		}
	}
	
	@Override
	public void clearRetrievalQueue() { this.worldGenQueueRef.set(null); }
	
	
	public boolean generationStepsAreFullyGenerated(ByteArrayList columnGenerationSteps)
	{
		return generationStepsHaveUsableCoverage(columnGenerationSteps);
	}
	static boolean generationStepsHaveUsableCoverage(ByteArrayList columnGenerationSteps)
	{
		return generationStepsHaveUsableCoverage(columnGenerationSteps, EDhApiWorldGenerationStep.EMPTY);
	}
	/** @return true if every column was generated at least to {@code requiredWorldGenStep} */
	static boolean generationStepsHaveUsableCoverage(ByteArrayList columnGenerationSteps, EDhApiWorldGenerationStep requiredWorldGenStep)
	{
		return columnGenerationSteps.size() == FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH
			&& IntStream.range(0, columnGenerationSteps.size())
			.noneMatch((int intValue) ->
			{
				byte value = columnGenerationSteps.getByte(intValue);
				return value == EDhApiWorldGenerationStep.EMPTY.value
					|| value == EDhApiWorldGenerationStep.DOWN_SAMPLED.value
					|| value < requiredWorldGenStep.value;
			});
	}
	private boolean hasUsableGeneratedCoverage(long pos)
	{ return this.hasUsableGeneratedCoverage(pos, this.getRequiredWorldGenStep(pos)); }
	private boolean hasUsableGeneratedCoverage(long pos, EDhApiWorldGenerationStep requiredWorldGenStep)
	{
		if (!this.repo.existsWithKey(pos))
		{
			return false;
		}
		
		try(PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutByteArrays(1))
		{
			ByteArrayList columnGenerationSteps = checkout.getByteArray(
				0,
				FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
			this.repo.getColumnGenerationStepForPos(pos, columnGenerationSteps);
			return generationStepsHaveUsableCoverage(columnGenerationSteps, requiredWorldGenStep);
		}
	}
	private EDhApiWorldGenerationStep getRequiredWorldGenStep(long pos)
	{
		EDhApiGeneratorPlan genPlan = this.getGeneratorPlan();
		if (genPlan == EDhApiGeneratorPlan.SURFACE_ONLY)
		{
			return EDhApiWorldGenerationStep.SURFACE;
		}
		else if (genPlan.surfaceGenEnabled
			&& DhSectionPos.getDetailLevel(pos) > DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL)
		{
			return EDhApiWorldGenerationStep.SURFACE;
		}
		else
		{
			return EDhApiWorldGenerationStep.FEATURES;
		}
	}
	
	
	@Override
	public LongArrayList getPositionsToRetrieve(long pos, byte generatorDetailLevel)
	{
		IFullDataSourceRetrievalQueue worldGenQueue = this.worldGenQueueRef.get();
		if (worldGenQueue == null)
		{
			return null;
		}
		
		
		EDhApiWorldGenerationStep requiredWorldGenStep = this.getRequiredWorldGenStep(pos);
		
		// don't check any child positions if this position is already fully generated
		if (this.hasUsableGeneratedCoverage(pos, requiredWorldGenStep))
		{
			return new LongArrayList();
		}
		
		
		
		// this section is missing one or more columns, queue the missing ones for generation.
		LongArrayList generationList = new LongArrayList();
		
		DhSectionPos.forEachChildAtDetailLevel(pos, generatorDetailLevel, (genPos) ->
		{
			if (!this.hasUsableGeneratedCoverage(genPos, requiredWorldGenStep))
			{
				// this position is missing one or more generated columns
				generationList.add(genPos);
			}
		});
		
		return generationList;
	}
	
	//endregion world gen
	
	
	
	//================//
	// base overrides //
	//================//
	//region base overrides
	
	@Override 
	public void close()
	{
		super.close();
		
		this.delayedFullDataSourceSaveCache.close();
	}
	
	//endregion base overrides
	
	
	
	
	//================//
	// helper classes //
	//================//
	//region helper classes
	
	private CompletableFuture<Void> onDataSourceSaveAsync(FullDataSourceV2 fullDataSource) 
	{ return this.updateDataSourceAsync(fullDataSource); }
	
	/** used by external event listeners */
	public interface IOnWorldGenCompleteListener
	{
		boolean shouldDoWorldGen();
		
		DhBlockPos2D getTargetPosForGeneration();
		
		/** Fired whenever a section has completed generating */
		void onWorldGenTaskComplete(long pos);
		
	}
	 
	//endregion helper classes
	
	
}
