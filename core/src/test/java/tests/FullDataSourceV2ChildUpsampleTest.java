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

public class FullDataSourceV2ChildUpsampleTest
{
	@Test
	public void childUpsampleCopiesParentColumnsBeforeRemapping() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encode((byte) 7, 0, 0);
		long childPos = DhSectionPos.getChildByIndex(parentPos, 0);
		int sourceIndex = FullDataSourceV2.relativePosToIndex(0, 0);
		
		FullDataPointIdMap parentMapping = new FullDataPointIdMap(parentPos);
		int parentIdA = addMapping(parentMapping, "parent_a");
		int parentIdB = addMapping(parentMapping, "parent_b");
		LongArrayList[] parentColumns = emptyColumns();
		parentColumns[sourceIndex].add(FullDataPointUtil.encode(parentIdB, 10, 100, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT));
		parentColumns[sourceIndex].add(FullDataPointUtil.encode(parentIdA, 100, 0, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT));
		
		FullDataPointIdMap childMapping = new FullDataPointIdMap(childPos);
		addMapping(childMapping, "child_existing");
		
		try (
			FullDataSourceV2 parent = FullDataSourceV2.createWithData(
				parentPos,
				parentMapping,
				parentColumns,
				filledGenerationSteps(EDhApiWorldGenerationStep.FEATURES),
				filledCompressionModes(EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS)
			);
			FullDataSourceV2 child = FullDataSourceV2.createWithData(
				childPos,
				childMapping,
				emptyColumns(),
				filledGenerationSteps(EDhApiWorldGenerationStep.EMPTY),
				filledCompressionModes(EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS)
			)
		)
		{
			LongArrayList parentColumnBefore = new LongArrayList(parent.dataPoints[sourceIndex]);
			
			Assert.assertTrue(child.updateFromDataSource(parent));
			Assert.assertEquals(parentColumnBefore, parent.dataPoints[sourceIndex]);
			Assert.assertNotSame(parent.dataPoints[sourceIndex], child.dataPoints[sourceIndex]);
		}
	}
	
	@Test
	public void childUpsampleKeepsExistingChildColumnMetadata() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encode((byte) 7, 0, 0);
		long childPos = DhSectionPos.getChildByIndex(parentPos, 0);
		int targetIndex = FullDataSourceV2.relativePosToIndex(0, 0);
		
		FullDataPointIdMap parentMapping = new FullDataPointIdMap(parentPos);
		int parentId = addMapping(parentMapping, "parent");
		LongArrayList[] parentColumns = emptyColumns();
		parentColumns[targetIndex].add(FullDataPointUtil.encode(parentId, 16, 0, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT));
		
		FullDataPointIdMap childMapping = new FullDataPointIdMap(childPos);
		int childId = addMapping(childMapping, "child");
		LongArrayList[] childColumns = emptyColumns();
		childColumns[targetIndex].add(FullDataPointUtil.encode(childId, 16, 0, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT));
		byte[] childGenerationSteps = filledGenerationSteps(EDhApiWorldGenerationStep.EMPTY);
		childGenerationSteps[targetIndex] = EDhApiWorldGenerationStep.FEATURES.value;
		byte[] childCompressionModes = filledCompressionModes(EDhApiWorldCompressionMode.VISUALLY_EQUAL);
		childCompressionModes[targetIndex] = EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value;
		
		try (
			FullDataSourceV2 parent = FullDataSourceV2.createWithData(
				parentPos,
				parentMapping,
				parentColumns,
				filledGenerationSteps(EDhApiWorldGenerationStep.FEATURES),
				filledCompressionModes(EDhApiWorldCompressionMode.VISUALLY_EQUAL)
			);
			FullDataSourceV2 child = FullDataSourceV2.createWithData(childPos, childMapping, childColumns, childGenerationSteps, childCompressionModes)
		)
		{
			Assert.assertTrue(child.updateFromDataSource(parent));
			Assert.assertEquals(EDhApiWorldGenerationStep.FEATURES.value, child.columnGenerationSteps.getByte(targetIndex));
			Assert.assertEquals(EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value, child.columnWorldCompressionMode.getByte(targetIndex));
		}
	}
	
	@Test
	public void childUpsampleUsesParentSourceColumnCompression() throws DataCorruptedException
	{
		long parentPos = DhSectionPos.encode((byte) 7, 0, 0);
		long childPos = DhSectionPos.getChildByIndex(parentPos, 0);
		int targetIndex = FullDataSourceV2.relativePosToIndex(2, 0);
		int sourceIndex = FullDataSourceV2.relativePosToIndex(1, 0);
		
		FullDataPointIdMap parentMapping = new FullDataPointIdMap(parentPos);
		int parentId = addMapping(parentMapping, "parent");
		LongArrayList[] parentColumns = emptyColumns();
		parentColumns[sourceIndex].add(FullDataPointUtil.encode(parentId, 16, 0, LodUtil.MIN_MC_LIGHT, LodUtil.MAX_MC_LIGHT));
		byte[] parentCompressionModes = filledCompressionModes(EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
		parentCompressionModes[sourceIndex] = EDhApiWorldCompressionMode.VISUALLY_EQUAL.value;
		
		try (
			FullDataSourceV2 parent = FullDataSourceV2.createWithData(
				parentPos,
				parentMapping,
				parentColumns,
				filledGenerationSteps(EDhApiWorldGenerationStep.FEATURES),
				parentCompressionModes
			);
			FullDataSourceV2 child = FullDataSourceV2.createWithData(
				childPos,
				new FullDataPointIdMap(childPos),
				emptyColumns(),
				filledGenerationSteps(EDhApiWorldGenerationStep.EMPTY),
				filledCompressionModes(EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS)
			)
		)
		{
			Assert.assertTrue(child.updateFromDataSource(parent));
			Assert.assertEquals(EDhApiWorldGenerationStep.DOWN_SAMPLED.value, child.columnGenerationSteps.getByte(targetIndex));
			Assert.assertEquals(EDhApiWorldCompressionMode.VISUALLY_EQUAL.value, child.columnWorldCompressionMode.getByte(targetIndex));
		}
	}
	
	private static int addMapping(FullDataPointIdMap mapping, String name)
	{
		return mapping.addIfNotPresentAndGetId(new TestBiomeWrapper(name), new TestBlockStateWrapper(name));
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
	
	private static byte[] filledGenerationSteps(EDhApiWorldGenerationStep generationStep)
	{
		byte[] values = new byte[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		Arrays.fill(values, generationStep.value);
		return values;
	}
	
	private static byte[] filledCompressionModes(EDhApiWorldCompressionMode compressionMode)
	{
		byte[] values = new byte[FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH];
		Arrays.fill(values, compressionMode.value);
		return values;
	}
}
