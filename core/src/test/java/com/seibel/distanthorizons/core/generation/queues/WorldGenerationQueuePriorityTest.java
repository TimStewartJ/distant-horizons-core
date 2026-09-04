package com.seibel.distanthorizons.core.generation.queues;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class WorldGenerationQueuePriorityTest
{
	@Test
	public void farPriorityTaskWinsWhileBelowQuota()
	{
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY,
			1, IDhApiWorldGenerator.GENERATION_READY,
			11, 16, 0));
		Assert.assertFalse(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_READY,
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY,
			11, 16, 0));
	}

	@Test
	public void nearbyReadyTaskWinsAtPriorityQuota()
	{
		Assert.assertFalse(WorldGenerationQueue.shouldPreferTask(
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY,
			1, IDhApiWorldGenerator.GENERATION_READY,
			12, 16, 0));
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_READY,
			10_000, IDhApiWorldGenerator.GENERATION_PRIORITY,
			12, 16, 0));
		Assert.assertEquals(12, WorldGenerationQueue.getPriorityTaskLimit(16));
	}

	@Test
	public void selectionIsWorkConservingAndDistanceOrderedWithinEachLane()
	{
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_READY,
			2, IDhApiWorldGenerator.GENERATION_READY,
			12, 16, 0));
		Assert.assertTrue(WorldGenerationQueue.shouldPreferTask(
			1, IDhApiWorldGenerator.GENERATION_PRIORITY,
			2, IDhApiWorldGenerator.GENERATION_PRIORITY,
			16, 16, 0));
	}

	@Test
	public void integratedSelectionHonorsQuotaWorkConservationAndBackoff()
	{
		SelectionCandidate priority = new SelectionCandidate(1L, 10_000, IDhApiWorldGenerator.GENERATION_PRIORITY);
		SelectionCandidate normal = new SelectionCandidate(2L, 1, IDhApiWorldGenerator.GENERATION_READY);

		WorldGenerationQueue.TaskSelection<SelectionCandidate> selectedTask =
			WorldGenerationQueue.selectPreferredTask(
				Arrays.asList(priority, normal),
				candidate -> candidate.availability,
				candidate -> candidate.distance,
				12,
				16,
				0);
		Assert.assertSame(normal, selectedTask.task);

		selectedTask = WorldGenerationQueue.selectPreferredTask(
			Collections.singletonList(priority),
			candidate -> candidate.availability,
			candidate -> candidate.distance,
			12,
			16,
			0);
		Assert.assertSame(priority, selectedTask.task);

		long now = TimeUnit.SECONDS.toNanos(10);
		WorldGenerationQueue.GenerationRetryBackoffTracker backoffTracker =
			new WorldGenerationQueue.GenerationRetryBackoffTracker();
		backoffTracker.recordFailure(priority.pos, now);
		selectedTask = WorldGenerationQueue.selectPreferredTask(
			Arrays.asList(priority, normal),
			candidate -> backoffTracker.isBackingOff(candidate.pos, now)
				? IDhApiWorldGenerator.GENERATION_WAIT
				: candidate.availability,
			candidate -> candidate.distance,
			0,
			16,
			0);
		Assert.assertSame(normal, selectedTask.task);
	}

	@Test
	public void oneThreadSelectionProvidesThreeToOneServiceAndRemainsWorkConserving()
	{
		SelectionCandidate priority = new SelectionCandidate(1L, 10_000, IDhApiWorldGenerator.GENERATION_PRIORITY);
		SelectionCandidate normal = new SelectionCandidate(2L, 1, IDhApiWorldGenerator.GENERATION_READY);
		byte[] actualSelections = new byte[8];
		int weightedStartIndex = 0;

		for (int i = 0; i < actualSelections.length; i++)
		{
			WorldGenerationQueue.TaskSelection<SelectionCandidate> selectedTask =
				WorldGenerationQueue.selectPreferredTask(
					Arrays.asList(priority, normal),
					candidate -> candidate.availability,
					candidate -> candidate.distance,
					0,
					1,
					weightedStartIndex);
			actualSelections[i] = selectedTask.availability;
			weightedStartIndex = WorldGenerationQueue.getNextWeightedStartIndex(weightedStartIndex);
		}

		Assert.assertArrayEquals(
			new byte[] {
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_READY,
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_PRIORITY,
				IDhApiWorldGenerator.GENERATION_READY
			},
			actualSelections);

		Assert.assertSame(
			priority,
			WorldGenerationQueue.selectPreferredTask(
				Collections.singletonList(priority),
				candidate -> candidate.availability,
				candidate -> candidate.distance,
				0,
				1,
				3).task);
		Assert.assertSame(
			normal,
			WorldGenerationQueue.selectPreferredTask(
				Collections.singletonList(normal),
				candidate -> candidate.availability,
				candidate -> candidate.distance,
				0,
				1,
				0).task);
	}

	@Test
	public void configuredCapacityIsStrict()
	{
		Assert.assertFalse(WorldGenerationQueue.isGeneratorAtCapacity(15, 16));
		Assert.assertTrue(WorldGenerationQueue.isGeneratorAtCapacity(16, 16));
		Assert.assertTrue(WorldGenerationQueue.isGeneratorAtCapacity(17, 16));
	}

	@Test
	public void queuedWorkerCanBeCancelledBeforeItStarts()
	{
		WorldGenerationQueue.QueueWorkerState workerState =
			new WorldGenerationQueue.QueueWorkerState();

		Assert.assertTrue(workerState.cancelBeforeStart());
		Assert.assertFalse(workerState.tryStart());
		Assert.assertTrue(workerState.completionFuture.isDone());
		Assert.assertFalse(workerState.completionFuture.isCompletedExceptionally());
	}

	@Test
	public void runningWorkerCompletesOnlyAfterItsBodyExits()
	{
		WorldGenerationQueue.QueueWorkerState workerState =
			new WorldGenerationQueue.QueueWorkerState();

		Assert.assertTrue(workerState.tryStart());
		Assert.assertFalse(workerState.cancelBeforeStart());
		Assert.assertFalse(workerState.completionFuture.isDone());
		workerState.complete();
		Assert.assertTrue(workerState.completionFuture.isDone());
	}

	@Test
	public void rejectedGenerationUsesPerPositionBoundedExponentialBackoffAndSuccessClearsIt()
	{
		WorldGenerationQueue.GenerationRetryBackoffTracker tracker =
			new WorldGenerationQueue.GenerationRetryBackoffTracker();
		long firstPos = 123L;
		long secondPos = 456L;
		long now = TimeUnit.SECONDS.toNanos(10);

		Assert.assertEquals(1_000, tracker.recordFailure(firstPos, now));
		Assert.assertTrue(tracker.isBackingOff(firstPos, now));
		Assert.assertFalse(tracker.isBackingOff(secondPos, now));
		Assert.assertEquals(1, tracker.getBackingOffPositionCount(now));
		Assert.assertFalse(tracker.isBackingOff(
			firstPos,
			now + TimeUnit.MILLISECONDS.toNanos(1_000)));
		Assert.assertEquals(
			0,
			tracker.getBackingOffPositionCount(now + TimeUnit.MILLISECONDS.toNanos(1_000)));

		Assert.assertEquals(
			2_000,
			tracker.recordFailure(firstPos, now + TimeUnit.MILLISECONDS.toNanos(1_000)));
		Assert.assertEquals(
			1,
			tracker.getBackingOffPositionCount(now + TimeUnit.MILLISECONDS.toNanos(1_000)));
		Assert.assertEquals(4_000, WorldGenerationQueue.calculateRejectionBackoffInMs(3));
		Assert.assertEquals(30_000, WorldGenerationQueue.calculateRejectionBackoffInMs(100));

		tracker.clear(firstPos);
		Assert.assertFalse(tracker.isBackingOff(firstPos, now));
		Assert.assertEquals(0, tracker.getBackingOffPositionCount(now));
	}

	@Test
	public void onlyActiveRejectedExecutionsAreRetryable()
	{
		CompletionException rejection =
			new CompletionException(new RejectedExecutionException("transient rejection"));

		Assert.assertTrue(WorldGenerationQueue.isRetryableGenerationRejection(rejection, false, false));
		Assert.assertFalse(WorldGenerationQueue.isRetryableGenerationRejection(rejection, true, false));
		Assert.assertFalse(WorldGenerationQueue.isRetryableGenerationRejection(rejection, false, true));
		Assert.assertFalse(WorldGenerationQueue.isRetryableGenerationRejection(
			new IllegalStateException("not retryable"),
			false,
			false));
	}

	@Test
	public void rawPriorityAncestorIsDiscoveredEvenWhenItsRunnableTaskIsBackingOff()
	{
		long requestedPos = DhSectionPos.encode((byte) 6, -33, 17);
		long expectedPriorityPos = DhSectionPos.convertToDetailLevel(requestedPos, (byte) 12);
		long now = TimeUnit.SECONDS.toNanos(10);
		WorldGenerationQueue.GenerationRetryBackoffTracker backoffTracker =
			new WorldGenerationQueue.GenerationRetryBackoffTracker();
		backoffTracker.recordFailure(expectedPriorityPos, now);
		Assert.assertTrue(backoffTracker.isBackingOff(expectedPriorityPos, now));

		long priorityPos = WorldGenerationQueue.findPriorityRetrievalPos(
			requestedPos,
			(byte) 24,
			(pos, dataDetail) -> dataDetail == 6
				? IDhApiWorldGenerator.GENERATION_PRIORITY
				: IDhApiWorldGenerator.GENERATION_READY);

		Assert.assertEquals(12, DhSectionPos.getDetailLevel(priorityPos));
		Assert.assertEquals(expectedPriorityPos, priorityPos);
	}

	@Test
	public void retrievalPositionIsUnchangedWithoutPriorityAvailability()
	{
		long requestedPos = DhSectionPos.encode((byte) 8, 9, -11);

		Assert.assertEquals(
			requestedPos,
			WorldGenerationQueue.findPriorityRetrievalPos(
				requestedPos,
				(byte) 24,
				(pos, dataDetail) -> IDhApiWorldGenerator.GENERATION_READY));
	}

	private static class SelectionCandidate
	{
		public final long pos;
		public final int distance;
		public final byte availability;

		private SelectionCandidate(long pos, int distance, byte availability)
		{
			this.pos = pos;
			this.distance = distance;
			this.availability = availability;
		}
	}
}
