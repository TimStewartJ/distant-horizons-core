/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 */

package tests;

import com.seibel.distanthorizons.api.enums.config.EDhApiDataCompressionMode;
import com.seibel.distanthorizons.api.enums.config.EDhApiWorldCompressionMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint;
import com.seibel.distanthorizons.core.dataObjects.fullData.FullDataPointIdMap;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.sql.dto.FullDataSourceV2DTO;
import com.seibel.distanthorizons.core.util.FullDataPointUtil;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Proxy;
import testItems.wrappers.TestBiomeWrapper;
import testItems.wrappers.TestBlockStateWrapper;

/** Headless timing simulation for DH work after a Tellus API data source is generated. */
public final class TellusLodIngestionSimulation
{
	private static final int DEFAULT_WARMUP_ITERATIONS = 4;
	private static final int DEFAULT_MEASURE_ITERATIONS = 20;

	private TellusLodIngestionSimulation() { }

	public static void main(String[] args) throws Exception
	{
		bindHeadlessWrapperFactory();
		int warmupIterations = intArgument(args, "--warmup=", DEFAULT_WARMUP_ITERATIONS);
		int measureIterations = intArgument(args, "--iterations=", DEFAULT_MEASURE_ITERATIONS);
		long parentPos = DhSectionPos.encode((byte) 10, 0, 0);
		FullDataSourceV2[] children = new FullDataSourceV2[4];
		try
		{
			for (int i = 0; i < children.length; i++)
			{
				children[i] = createTellusLikeSource(DhSectionPos.getChildByIndex(parentPos, i), i);
			}

			System.out.println(
				"DH_TELLUS_INGESTION_SIMULATION columns="+(FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH)
					+" points_per_column=6 tall_y=true compression=LZ4"
			);
			runBenchmark("encode_one_direct_lod", warmupIterations, measureIterations, () -> encodeAndClose(children[0]));
			runPairedBenchmark(
				"populate_api_columns_validation_off", () -> populateApiDataSource(false, false),
				"populate_api_columns_legacy_continuity_scan", () -> populateApiDataSource(false, true),
				Math.max(10, warmupIterations), measureIterations);
			runBenchmark("merge_four_children_sequential", warmupIterations, measureIterations, () -> mergeSequential(parentPos, children, false));
			runBenchmark("merge_four_children_batched", warmupIterations, measureIterations, () -> mergeBatched(parentPos, children, false));
			runBenchmark("encode_four_direct_lods", warmupIterations, measureIterations, () -> encodeChildren(children));
			runBenchmark("encode_four_plus_parent_propagation", warmupIterations, measureIterations, () -> {
				encodeChildren(children);
				mergeBatched(parentPos, children, true);
			});
		}
		finally
		{
			for (FullDataSourceV2 child : children)
			{
				if (child != null)
				{
					child.close();
				}
			}
		}
	}

	private static void bindHeadlessWrapperFactory()
	{
		final TestBlockStateWrapper air = new TestBlockStateWrapper("air");
		IWrapperFactory wrapperFactory = (IWrapperFactory) Proxy.newProxyInstance(
			IWrapperFactory.class.getClassLoader(),
			new Class<?>[] { IWrapperFactory.class },
			(proxy, method, args) ->
			{
				if (method.getName().equals("getAirBlockStateWrapper"))
				{
					return air;
				}
				if (method.getName().equals("getDelayedSetupComplete"))
				{
					return true;
				}
				if (method.getName().equals("toString"))
				{
					return "HeadlessTellusWrapperFactory";
				}
				Class<?> returnType = method.getReturnType();
				if (returnType == boolean.class) return false;
				if (returnType == byte.class) return (byte) 0;
				if (returnType == short.class) return (short) 0;
				if (returnType == int.class) return 0;
				if (returnType == long.class) return 0L;
				if (returnType == float.class) return 0.0F;
				if (returnType == double.class) return 0.0D;
				if (returnType == char.class) return (char) 0;
				return null;
			}
		);
		SingletonInjector.INSTANCE.bind(IWrapperFactory.class, wrapperFactory);
	}

	private static void populateApiDataSource(boolean validation, boolean legacyContinuityScan) throws Exception
	{
		long pos = DhSectionPos.encode((byte) 15, 0, 0);
		TestBiomeWrapper biome = new TestBiomeWrapper("tellus_api_biome");
		TestBlockStateWrapper block = new TestBlockStateWrapper("tellus_api_block");
		List<List<DhApiTerrainDataPoint>> variants = new ArrayList<>();
		for (int variant = 0; variant < 8; variant++)
		{
			int top = 8_900 + variant * 8;
			List<DhApiTerrainDataPoint> column = new ArrayList<>();
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top, top + 3, block, biome));
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top - 8, top, block, biome));
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top - 9, top - 8, block, biome));
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 15, top - 18, top - 9, block, biome));
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 0, 32, top - 18, block, biome));
			column.add(DhApiTerrainDataPoint.create((byte) 0, 0, 0, 0, 32, block, biome));
			variants.add(column);
		}

		try (FullDataSourceV2 source = FullDataSourceV2.createEmpty(pos))
		{
			source.setRunApiSetterValidation(validation);
			for (int x = 0; x < FullDataSourceV2.WIDTH; x++)
			{
				for (int z = 0; z < FullDataSourceV2.WIDTH; z++)
				{
					List<DhApiTerrainDataPoint> column = variants.get((x + z) & 7);
					if (legacyContinuityScan)
					{
						LodDataBuilder.putListInTopDownOrder(column);
						LongArrayList packed = LodDataBuilder.convertApiDataPointListToPackedLongArray(column, source, 0, true);
						source.setSingleColumn(
							packed, x, z, EDhApiWorldGenerationStep.SURFACE, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS);
					}
					else
					{
						source.setApiDataPointColumn(x, z, column);
					}
				}
			}
		}
	}

	private static void runBenchmark(String name, int warmupIterations, int measureIterations, CheckedRunnable action) throws Exception
	{
		for (int i = 0; i < warmupIterations; i++)
		{
			action.run();
		}
		long[] elapsed = new long[measureIterations];
		for (int i = 0; i < elapsed.length; i++)
		{
			long start = System.nanoTime();
			action.run();
			elapsed[i] = System.nanoTime() - start;
		}
		printTiming(name, elapsed);
	}

	private static void runPairedBenchmark(
		String firstName, CheckedRunnable firstAction,
		String secondName, CheckedRunnable secondAction,
		int warmupIterations, int measureIterations) throws Exception
	{
		for (int i = 0; i < warmupIterations; i++)
		{
			firstAction.run();
			secondAction.run();
		}
		long[] firstElapsed = new long[measureIterations];
		long[] secondElapsed = new long[measureIterations];
		for (int i = 0; i < measureIterations; i++)
		{
			if ((i & 1) == 0)
			{
				firstElapsed[i] = time(firstAction);
				secondElapsed[i] = time(secondAction);
			}
			else
			{
				secondElapsed[i] = time(secondAction);
				firstElapsed[i] = time(firstAction);
			}
		}
		printTiming(firstName, firstElapsed);
		printTiming(secondName, secondElapsed);
	}

	private static long time(CheckedRunnable action) throws Exception
	{
		long start = System.nanoTime();
		action.run();
		return System.nanoTime() - start;
	}

	private static void printTiming(String name, long[] elapsed)
	{
		Arrays.sort(elapsed);
		double medianMs = elapsed[elapsed.length / 2] / 1_000_000.0;
		double p95Ms = elapsed[Math.min(elapsed.length - 1, (int) Math.ceil(elapsed.length * 0.95) - 1)] / 1_000_000.0;
		double totalMs = 0.0;
		for (long value : elapsed)
		{
			totalMs += value / 1_000_000.0;
		}
		System.out.printf(
			Locale.ROOT,
			"DH_TELLUS_PHASE name=%s iterations=%d mean_ms=%.3f median_ms=%.3f p95_ms=%.3f%n",
			name, elapsed.length, totalMs / elapsed.length, medianMs, p95Ms
		);
	}

	private static void encodeChildren(FullDataSourceV2[] children) throws Exception
	{
		for (FullDataSourceV2 child : children)
		{
			encodeAndClose(child);
		}
	}

	private static void encodeAndClose(FullDataSourceV2 source) throws Exception
	{
		try (FullDataSourceV2DTO ignored = FullDataSourceV2DTO.CreateFromDataSource(source, EDhApiDataCompressionMode.LZ4))
		{
			// Closing returns all DTO buffers to DH's normal pool.
		}
	}

	private static void mergeSequential(long parentPos, FullDataSourceV2[] children, boolean encode) throws Exception
	{
		try (FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos))
		{
			for (FullDataSourceV2 child : children)
			{
				parent.updateFromDataSource(child);
			}
			if (encode)
			{
				encodeAndClose(parent);
			}
		}
	}

	private static void mergeBatched(long parentPos, FullDataSourceV2[] children, boolean encode) throws Exception
	{
		try (FullDataSourceV2 parent = FullDataSourceV2.createEmpty(parentPos))
		{
			try (FullDataSourceV2.UpdateBatch batch = parent.beginUpdateBatch())
			{
				for (FullDataSourceV2 child : children)
				{
					batch.updateFromDataSource(child);
				}
			}
			if (encode)
			{
				encodeAndClose(parent);
			}
		}
	}

	private static FullDataSourceV2 createTellusLikeSource(long pos, int seed) throws Exception
	{
		FullDataPointIdMap mapping = new FullDataPointIdMap(pos);
		int[] ids = new int[8];
		for (int i = 0; i < ids.length; i++)
		{
			String name = "tellus_"+seed+"_"+i;
			ids[i] = mapping.addIfNotPresentAndGetId(new TestBiomeWrapper(name), new TestBlockStateWrapper(name));
		}

		int columnCount = FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH;
		LongArrayList[] columns = new LongArrayList[columnCount];
		for (int index = 0; index < columnCount; index++)
		{
			int id = ids[(index + seed) & 7];
			int terrainTop = 8_850 + ((index * 31 + seed * 17) & 255);
			columns[index] = new LongArrayList(new long[] {
				FullDataPointUtil.encode(id, 3, terrainTop + 18, (byte) 0, (byte) 15),
				FullDataPointUtil.encode(id, 8, terrainTop + 10, (byte) 0, (byte) 15),
				FullDataPointUtil.encode(id, 1, terrainTop + 9, (byte) 0, (byte) 15),
				FullDataPointUtil.encode(id, 9, terrainTop, (byte) 0, (byte) 15),
				FullDataPointUtil.encode(id, 96, terrainTop - 96, (byte) 0, (byte) 0),
				FullDataPointUtil.encode(id, 32, 0, (byte) 0, (byte) 0)
			});
		}

		byte[] generationSteps = new byte[columnCount];
		Arrays.fill(generationSteps, EDhApiWorldGenerationStep.FEATURES.value);
		byte[] compressionModes = new byte[columnCount];
		Arrays.fill(compressionModes, EDhApiWorldCompressionMode.MERGE_SAME_BLOCKS.value);
		FullDataSourceV2 source = FullDataSourceV2.createWithData(pos, mapping, columns, generationSteps, compressionModes);
		source.applyToParent = true;
		source.applyToChildren = true;
		return source;
	}

	private static int intArgument(String[] args, String prefix, int defaultValue)
	{
		for (String arg : args)
		{
			if (arg.startsWith(prefix))
			{
				return Math.max(1, Integer.parseInt(arg.substring(prefix.length())));
			}
		}
		return defaultValue;
	}

	private interface CheckedRunnable
	{
		void run() throws Exception;
	}
}
