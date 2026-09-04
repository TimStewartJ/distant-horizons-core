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

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.types.ConfigEntry;
import com.seibel.distanthorizons.core.util.RenderDataPointUtil;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Tellus compiles against the stock Distant Horizons API and reaches this fork's
 * additions reflectively, by class and member name
 * (see Tellus {@code DistantHorizonsRuntimeConfigGuard} and {@code DistantHorizonsIntegration}).
 * A rename here would not fail any build; Tellus would silently fall back to stock behavior.
 * This test pins the names Tellus depends on so a rename fails loudly in this repository.
 */
public class TellusReflectionContractTest
{
	private static final String CONFIG = "com.seibel.distanthorizons.core.config.Config";
	private static final String WORLD_GENERATION_QUEUE =
		"com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue";
	
	
	
	@Test
	public void configEntriesTellusOverridesAtRuntime() throws Exception
	{
		this.assertConfigEntry(CONFIG + "$Server$Experimental", "enableNSizedGeneration", Boolean.class);
		this.assertConfigEntry(CONFIG + "$Common$LodBuilding$Experimental", "upsampleLowerDetailLodsToFillHoles", Boolean.class);
		this.assertConfigEntry(CONFIG + "$Common$LodBuilding$Experimental", "keepLowerDetailLodsUntilChildrenHaveData", Boolean.class);
		this.assertConfigEntry(CONFIG + "$Common$WorldGenerator", "pauseGenerationAboveCameraSpeed", Double.class);
	}
	
	@Test
	public void configEntryAccessorsUsedByTellus() throws Exception
	{
		Method get = ConfigEntry.class.getMethod("get");
		Method setWithoutSaving = ConfigEntry.class.getMethod("setWithoutSaving", Object.class);
		Assert.assertTrue(Modifier.isPublic(get.getModifiers()));
		Assert.assertTrue(Modifier.isPublic(setWithoutSaving.getModifiers()));
	}
	
	@Test
	public void renderYRangeReadByTellus() throws Exception
	{
		Field field = RenderDataPointUtil.class.getField("MAX_WORLD_Y_SIZE");
		Assert.assertEquals(int.class, field.getType());
		Assert.assertTrue(Modifier.isStatic(field.getModifiers()));
		// Tellus refuses "Increase Height" worlds when this is smaller than the generator depth.
		Assert.assertEquals(1 << 14, field.getInt(null));
	}
	
	@Test
	public void generationAvailabilityHookImplementedByTellus() throws Exception
	{
		Method method = IDhApiWorldGenerator.class.getMethod(
				"getGenerationAvailability", int.class, int.class, int.class, byte.class);
		Assert.assertEquals(byte.class, method.getReturnType());
		Assert.assertTrue("Tellus generators compiled against the stock API must still load",
				method.isDefault());
	}
	
	@Test
	public void rejectedGenerationBackoffCapabilityReadByTellus() throws Exception
	{
		Class<?> queueClass = Class.forName(
			WORLD_GENERATION_QUEUE,
			false,
			TellusReflectionContractTest.class.getClassLoader());
		Field field = queueClass.getField("SUPPORTS_REJECTED_GENERATION_BACKOFF");

		Assert.assertEquals(boolean.class, field.getType());
		Assert.assertTrue(Modifier.isPublic(field.getModifiers()));
		Assert.assertTrue(Modifier.isStatic(field.getModifiers()));
		Assert.assertTrue(Modifier.isFinal(field.getModifiers()));
		Assert.assertTrue(field.getBoolean(null));
	}

	
	
	private void assertConfigEntry(String ownerClassName, String fieldName, Class<?> valueType) throws Exception
	{
		// Resolve without initializing Config: this test pins names and types, not defaults.
		Class<?> owner = Class.forName(ownerClassName, false, TellusReflectionContractTest.class.getClassLoader());
		Field field = owner.getField(fieldName);
		Assert.assertTrue(fieldName + " must be static", Modifier.isStatic(field.getModifiers()));
		Assert.assertEquals(fieldName + " must be a ConfigEntry", ConfigEntry.class, field.getType());
		
		Type generic = field.getGenericType();
		Assert.assertTrue(fieldName + " must declare its value type", generic instanceof ParameterizedType);
		Type[] typeArguments = ((ParameterizedType) generic).getActualTypeArguments();
		Assert.assertEquals(fieldName + " value type", valueType, typeArguments[0]);
	}
	
}
