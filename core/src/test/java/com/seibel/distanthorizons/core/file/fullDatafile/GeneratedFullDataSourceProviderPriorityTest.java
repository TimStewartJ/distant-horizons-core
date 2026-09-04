package com.seibel.distanthorizons.core.file.fullDatafile;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import org.junit.Assert;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class GeneratedFullDataSourceProviderPriorityTest
{
	@Test
	public void fullyGeneratedPriorityAncestorIsNotQueuedAgain()
	{
		long requestedPos = 123L;
		long priorityPos = 456L;
		ByteArrayList generationSteps = createGenerationSteps(EDhApiWorldGenerationStep.LIGHT);

		Assert.assertFalse(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			requestedPos,
			priorityPos,
			pos -> false,
			pos -> GeneratedFullDataSourceProvider.generationStepsHaveUsableCoverage(generationSteps)));
	}

	@Test
	public void genuinelyMissingPriorityAncestorIsStillQueued()
	{
		long requestedPos = 123L;
		long priorityPos = 456L;

		Assert.assertTrue(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			requestedPos,
			priorityPos,
			pos -> false,
			pos -> false));
	}

	@Test
	public void emptyStoredPriorityAncestorIsQueuedAgain()
	{
		ByteArrayList generationSteps = new ByteArrayList();

		Assert.assertTrue(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			123L,
			456L,
			pos -> false,
			pos -> GeneratedFullDataSourceProvider.generationStepsHaveUsableCoverage(generationSteps)));
	}

	@Test
	public void whollyDownsampledPriorityAncestorIsQueuedAgain()
	{
		ByteArrayList generationSteps = createGenerationSteps(EDhApiWorldGenerationStep.DOWN_SAMPLED);

		Assert.assertTrue(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			123L,
			456L,
			pos -> false,
			pos -> GeneratedFullDataSourceProvider.generationStepsHaveUsableCoverage(generationSteps)));
	}

	@Test
	public void incompletelyGeneratedPriorityAncestorIsQueuedAgain()
	{
		ByteArrayList generationSteps = createGenerationSteps(EDhApiWorldGenerationStep.LIGHT);
		generationSteps.set(0, EDhApiWorldGenerationStep.EMPTY.value);

		Assert.assertTrue(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			123L,
			456L,
			pos -> false,
			pos -> GeneratedFullDataSourceProvider.generationStepsHaveUsableCoverage(generationSteps)));
	}

	@Test
	public void queuedPriorityAncestorAvoidsStorageLookup()
	{
		AtomicBoolean storageChecked = new AtomicBoolean(false);

		Assert.assertFalse(GeneratedFullDataSourceProvider.shouldQueuePriorityRetrieval(
			123L,
			456L,
			pos -> true,
			pos ->
			{
				storageChecked.set(true);
				return false;
			}));
		Assert.assertFalse(storageChecked.get());
	}

	private static ByteArrayList createGenerationSteps(EDhApiWorldGenerationStep generationStep)
	{
		ByteArrayList generationSteps = new ByteArrayList(FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
		for (int i = 0; i < FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH; i++)
		{
			generationSteps.add(generationStep.value);
		}
		return generationSteps;
	}
}
