/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.dataStreams.DhDataOutputStream;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.io.IOException;
import java.util.Arrays;
import org.junit.Assert;
import org.junit.Test;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

public class TallDataPointPackingTest
{
	private static final int TELLUS_BOTTOM_Y = 9_000;
	private static final int TELLUS_TOP_Y = 9_168;

	@Test
	public void fullDataPointRoundTripsTallCoordinates() throws DataCorruptedException
	{
		int id = 0x0ABCDEF;
		long dataPoint = FullDataPointUtil.encode(
			id, TELLUS_TOP_Y - TELLUS_BOTTOM_Y, TELLUS_BOTTOM_Y, (byte) 13, (byte) 7);

		Assert.assertEquals(id, FullDataPointUtil.getId(dataPoint));
		Assert.assertEquals(TELLUS_TOP_Y - TELLUS_BOTTOM_Y, FullDataPointUtil.getHeight(dataPoint));
		Assert.assertEquals(TELLUS_BOTTOM_Y, FullDataPointUtil.getBottomY(dataPoint));
		Assert.assertEquals(13, FullDataPointUtil.getBlockLight(dataPoint));
		Assert.assertEquals(7, FullDataPointUtil.getSkyLight(dataPoint));

		long boundary = FullDataPointUtil.encode(
			(int) FullDataPointUtil.ID_MASK, 1, RenderDataPointUtil.MAX_WORLD_Y_SIZE - 2, (byte) 15, (byte) 15);
		Assert.assertEquals((int) FullDataPointUtil.ID_MASK, FullDataPointUtil.getId(boundary));
		Assert.assertEquals(RenderDataPointUtil.MAX_WORLD_Y_SIZE - 2, FullDataPointUtil.getBottomY(boundary));
	}

	@Test
	public void renderDataPointRoundTripsTallCoordinatesAndPreservesFields()
	{
		long dataPoint = RenderDataPointUtil.createDataPoint(
			TELLUS_TOP_Y, TELLUS_BOTTOM_Y, 0xFF_FF_7F_AC, 15, 12, 15);

		Assert.assertEquals(TELLUS_TOP_Y, RenderDataPointUtil.getYMax(dataPoint));
		Assert.assertEquals(TELLUS_BOTTOM_Y, RenderDataPointUtil.getYMin(dataPoint));
		Assert.assertEquals(15, RenderDataPointUtil.getLightSky(dataPoint));
		Assert.assertEquals(12, RenderDataPointUtil.getLightBlock(dataPoint));
		Assert.assertEquals(15, RenderDataPointUtil.getBlockMaterialId(dataPoint));
		Assert.assertEquals(0xFF_FF_7E_AE, RenderDataPointUtil.getColor(dataPoint));

		long shifted = RenderDataPointUtil.shiftHeightAndDepth(dataPoint, (short) 100);
		Assert.assertEquals(TELLUS_TOP_Y + 100, RenderDataPointUtil.getYMax(shifted));
		Assert.assertEquals(TELLUS_BOTTOM_Y + 100, RenderDataPointUtil.getYMin(shifted));
		Assert.assertEquals(RenderDataPointUtil.getColor(dataPoint), RenderDataPointUtil.getColor(shifted));

		long recolored = RenderDataPointUtil.setBlue(
			RenderDataPointUtil.setGreen(RenderDataPointUtil.setRed(dataPoint, 0), 0), 0);
		Assert.assertEquals(0xFF_00_00_00, RenderDataPointUtil.getColor(recolored));
		Assert.assertEquals(TELLUS_TOP_Y, RenderDataPointUtil.getYMax(recolored));
		Assert.assertEquals(TELLUS_BOTTOM_Y, RenderDataPointUtil.getYMin(recolored));
	}

	@Test
	public void tallCoordinatesRoundTripThroughV3Dto() throws DataCorruptedException, IOException, InterruptedException
	{
		long pos = DhSectionPos.encode((byte) 6, 0, 0);
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int id = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("tall"), new TestBlockStateWrapper("tall"));
		long expected = FullDataPointUtil.encode(
			id, TELLUS_TOP_Y - TELLUS_BOTTOM_Y, TELLUS_BOTTOM_Y, (byte) 4, (byte) 15);

		LongArrayList[] columns = emptyColumns();
		int targetIndex = FullDataSourceV2.relativePosToIndex(10, 10);
		columns[targetIndex].add(expected);
		byte[] generationSteps = new byte[columns.length];
		Arrays.fill(generationSteps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compressionModes = new byte[columns.length];
		Arrays.fill(compressionModes, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);

		try (
			FullDataSourceV2 source = FullDataSourceV2.createWithData(
				pos, mapping, columns, generationSteps, compressionModes);
			FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.UNCOMPRESSED);
			FullDataSourceV2 decoded = dto.createUnitTestDataSource()
		)
		{
			Assert.assertEquals(FullDataSourceV2DTO.DATA_FORMAT.V3_TELLUS_TALL_Y, dto.dataFormatVersion);
			Assert.assertEquals(expected, decoded.dataPoints[targetIndex].getLong(0));
		}
	}

	@Test
	public void literalLegacyV1PackedDataConvertsToCurrentLayout() throws DataCorruptedException, IOException, InterruptedException
	{
		long pos = DhSectionPos.encode((byte) 6, 0, 0);
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int id = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper("legacy"), new TestBlockStateWrapper("legacy"));
		int height = 123;
		int bottomY = 3_500;
		byte blockLight = 11;
		byte skyLight = 6;
		long expected = FullDataPointUtil.encode(id, height, bottomY, blockLight, skyLight);

		LongArrayList[] columns = emptyColumns();
		columns[0].add(expected);
		byte[] generationSteps = new byte[columns.length];
		Arrays.fill(generationSteps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compressionModes = new byte[columns.length];
		Arrays.fill(compressionModes, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);

		try (
			FullDataSourceV2 source = FullDataSourceV2.createWithData(
				pos, mapping, columns, generationSteps, compressionModes);
			FullDataSourceV2DTO dto = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.UNCOMPRESSED)
		)
		{
			long literalV1DataPoint = ((long) id)
				| ((long) height << 32)
				| ((long) bottomY << 44)
				| ((long) skyLight << 56)
				| ((long) blockLight << 60);
			try (DhDataOutputStream out = DhDataOutputStream.create(
				EDhApiDataCompressionMode.UNCOMPRESSED, dto.compressedDataByteArray))
			{
				for (int i = 0; i < columns.length; i++)
				{
					out.writeShort(i == 0 ? 1 : 0);
					if (i == 0)
					{
						out.writeLong(literalV1DataPoint);
					}
				}
			}

			dto.dataFormatVersion = FullDataSourceV2DTO.DATA_FORMAT.V1_NO_ADJACENT_DATA;
			try (FullDataSourceV2 decoded = dto.createUnitTestDataSource())
			{
				Assert.assertEquals(expected, decoded.dataPoints[0].getLong(0));
			}
		}
	}

	private static LongArrayList[] emptyColumns()
	{
		LongArrayList[] columns = new LongArrayList[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		for (int i = 0; i < columns.length; i++)
		{
			columns[i] = new LongArrayList();
		}
		return columns;
	}
}
