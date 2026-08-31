/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

public class FullDataSourceV2UpdateBatchTest
{
	@Test
	public void batchedChildUpdatesMatchIndividuallyFinalizedUpdates() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encode((byte) 7, 0, 0);
		try (
			FullDataSourceV2 firstChild = createFilledChild(parentPos, 0, "first", 48, 9_000);
			FullDataSourceV2 secondChild = createFilledChild(parentPos, 3, "second", 96, 8_900);
			FullDataSourceV2 sequentialParent = FullDataSourceV2.createEmpty(parentPos);
			FullDataSourceV2 batchedParent = FullDataSourceV2.createEmpty(parentPos)
		)
		{
			Assert.assertTrue(sequentialParent.updateFromDataSource(firstChild));
			Assert.assertTrue(sequentialParent.updateFromDataSource(secondChild));

			try (FullDataSourceV2.UpdateBatch batch = batchedParent.beginUpdateBatch())
			{
				Assert.assertTrue(batch.updateFromDataSource(firstChild));
				Assert.assertTrue(batch.updateFromDataSource(secondChild));
				Assert.assertTrue(batch.hasDataChanged());
			}

			Assert.assertEquals(sequentialParent.mapping.size(), batchedParent.mapping.size());
			Assert.assertEquals(sequentialParent.columnGenerationSteps, batchedParent.columnGenerationSteps);
			Assert.assertEquals(sequentialParent.columnWorldCompressionMode, batchedParent.columnWorldCompressionMode);
			Assert.assertEquals(sequentialParent.hashCode(), batchedParent.hashCode());

			for (int i = 0; i < sequentialParent.dataPoints.length; i++)
			{
				Assert.assertEquals("Column mismatch at index "+i, sequentialParent.dataPoints[i], batchedParent.dataPoints[i]);
			}
		}
	}

	@Test
	public void closedBatchRejectsFurtherUpdates() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encode((byte) 7, 0, 0);
		try (
			FullDataSourceV2 child = createFilledChild(parentPos, 0, "child", 16, 100);
			FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos)
		)
		{
			FullDataSourceV2.UpdateBatch batch = parent.beginUpdateBatch();
			batch.close();

			try
			{
				batch.updateFromDataSource(child);
				Assert.fail("Expected a closed update batch to reject further updates.");
			}
			catch (IllegalStateException expected) { }
		}
	}

	private static FullDataSourceV2 createFilledChild(
		long parentPos, int childIndex, String mappingName, int height, int bottomY) throws DataCorruptedException
	{
		long childPos = DhSectionPos.getChildByIndex(parentPos, childIndex);
		FullDataPointIdMap mapping = new FullDataPointIdMap(childPos);
		int id = mapping.addIfNotPresentAndGetId(
			new TestBiomeWrapper(mappingName),
			new TestBlockStateWrapper(mappingName));

		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		long dataPoint = FullDataPointUtil.encode(id, height, bottomY, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT);
		for (int i = 0; i < columns.length; i++)
		{
			columns[i] = new LongArrayList(new long[] { dataPoint });
		}

		byte[] generationSteps = new byte[columns.length];
		Arrays.fill(generationSteps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compressionModes = new byte[columns.length];
		Arrays.fill(compressionModes, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);

		FullDataSourceV2 child = FullDataSourceV2.createWithData(
			childPos, mapping, columns, generationSteps, compressionModes);
		child.applyToParent = true;
		return child;
	}
}
