package dev.hephaestus.fiblib;

import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.PersistentState;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;

public class FibLib extends PersistentState {
	public static final String MOD_ID = "fiblib";
	private static final String MOD_NAME = "FibLib";

	private static final Logger LOGGER = LogManager.getLogger();
	private static final String SAVE_KEY = "fiblib";

	private static final HashMap<DimensionType, ArrayList<Pair<Block, BlockFib>>> PRE_LOAD = new HashMap<>();
	private static final HashSet<Block> ALL_FIB_BLOCKS = new HashSet<>();

	private static final Stack<Pair<Block, BlockPos>> PENDING_OVERWORLD_BLOCKS = new Stack<>();

	private final HashMap<Block, LongSet> blocks = new HashMap<>();
	private final HashMap<Block, BlockFib> fibs = new HashMap<>();

	static void log(String msg) {
		LOGGER.info(String.format("[%s] %s", MOD_NAME, msg));
	}

	static void log(String format, Object... args) {
		LOGGER.info(String.format("[%s] %s", MOD_NAME, String.format(format, args)));
	}

	// Construction methods
	private FibLib(ServerWorld world) {
		super(SAVE_KEY);

		this.markDirty();
	}

	private static FibLib getInstance(ServerWorld world) {
		FibLib instance = world.getPersistentStateManager().getOrCreate(() ->
			new FibLib(world), SAVE_KEY
		);

		int i = 0;
		while (!PENDING_OVERWORLD_BLOCKS.isEmpty()) {
			Pair<Block, BlockPos> pair = PENDING_OVERWORLD_BLOCKS.pop();
			instance.putWithInstance(pair.getLeft(), pair.getRight());
			world.getChunkManager().markForUpdate(pair.getRight());
			i++;
		}

		if (i > 0) {
			FibLib.log("Registered %d pre-loaded Block%s", i, i == 1 ? "" : "s");
		}

		return instance;
	}

	// Convenience
	private static FibLib getInstance(ServerPlayerEntity player) {
		return getInstance(player.getServerWorld());
	}

	/**
	 * Registers fibs that were added before the World they exist in was loaded. Called in ServerWorld constructor.
	 * @param world the world we are loading
	 */
	@Internal
	public static void registerPreloadedFibs(ServerWorld world) {
		int i = 0;
		if (PRE_LOAD.get(world.getDimension().getType()) != null) {
			for (Pair<Block, BlockFib> p : PRE_LOAD.get(world.getDimension().getType())) {
				FibLib.register(world, p.getLeft(), p.getRight());
				++i;
			}
		}

		if (i > 0) FibLib.log("Registered %d pre-loaded BlockFib%s", i, i == 1 ? "" : "s");
	}

	@Internal
	@Override
	public void fromTag(CompoundTag tag) {
		blocks.clear();

		CompoundTag fibTag = tag.getCompound(SAVE_KEY);

		for (String k : fibTag.getKeys()) {
			blocks.put(Registry.BLOCK.get(new Identifier(k)), new LongOpenHashSet(fibTag.getLongArray(k)));
		}
	}

	@Internal
	@Override
	public CompoundTag toTag(CompoundTag tag) {
		CompoundTag fibTag = new CompoundTag();
		for (Map.Entry<Block, LongSet> e : blocks.entrySet()) {
			fibTag.put(Registry.BLOCK.getId(e.getKey()).toString(), new LongArrayTag(e.getValue()));
		}

		tag.put(SAVE_KEY, fibTag);

		return tag;
	}

	// Instance methods. These are private to make the API simpler.

	// Because we only actually begin tracking the block if we have a fib that references it, it's safe to call put()
	// whenever and wherever we want.
	private void putWithInstance(Block block, BlockPos pos) {
		if (fibs.containsKey(block)) {
			blocks.putIfAbsent(block, new LongOpenHashSet());
			blocks.get(block).add(pos.asLong());
		}
	}

	private BlockState getWithInstance(BlockState state, ServerPlayerEntity player) {
		return fibs.getOrDefault(state.getBlock(), BlockFib.DEFAULT).get(state, player);
	}

	private void removeWithInstance(ServerWorld world, BlockPos pos) {
		if (blocks.containsKey(world.getBlockState(pos).getBlock()))
			blocks.get(world.getBlockState(pos).getBlock()).remove(pos.asLong());
	}

	// API methods
	/**
	 * Updates all tracked blocks in a given world. Somewhat expensive, and should probably not really be called. If you
	 * need to update multiple kinds of blocks, see the methods below
	 * @param world the world to update in
	 */
	public static void update(ServerWorld world) {
		int i = 0;
		for (Long l : Iterables.concat(FibLib.getInstance(world).blocks.values())) {
			world.getChunkManager().markForUpdate(BlockPos.fromLong(l));
			++i;
		}
		FibLib.log("Updated %d blocks", i);
	}

	/**
	 * Updates all of one kind of block.
	 * @param world the world to update in
	 * @param block the block type to update
	 */
	public static void update(ServerWorld world, Block block) {
		FibLib instance = FibLib.getInstance(world);
		if (instance.blocks.containsKey(block)) {
			int i = 0;
			for (Long l : instance.blocks.get(block)) {
				world.getChunkManager().markForUpdate(BlockPos.fromLong(l));
				++i;
			}
			FibLib.log("Updated %d blocks", i);
		}
	}

	/**
	 * Helper function for updating multiple kinds of blocks
	 * @param world the world to update in
	 * @param blocks the blocks to update
	 */
	public static void update(ServerWorld world, Block... blocks) {
		FibLib instance = FibLib.getInstance(world);

		int i = 0;
		for (Block a : blocks) {
			if (instance.blocks.containsKey(a)) {
				for (Long l : instance.blocks.get(a)) {
					world.getChunkManager().markForUpdate(BlockPos.fromLong(l));
					++i;
				}
			}
		}

		FibLib.log("Updated %d blocks", i);
	}

	/**
	 * Helper function for updating multiple kinds of blocks
	 * @param world the world to update in
	 * @param blocks the blocks to update
	 */
	public static void update(ServerWorld world, Collection<Block> blocks) {
		FibLib instance = FibLib.getInstance(world);

		int i = 0;
		for (Block a : blocks) {
			if (instance.blocks.containsKey(a)) {
				for (Long l : instance.blocks.get(a)) {
					world.getChunkManager().markForUpdate(BlockPos.fromLong(l));
					++i;
				}
			}
		}

		FibLib.log("Updated %d blocks", i);
	}


	/**
	 * Use this function to register Fibs when you already have access to a World.
	 * @param world the world the newly registered Fib will apply to
	 * @param block the block to be fibbed
	 * @param fib the fib itself. Can be a lambda expression for simpler fibs, or an implementation of BlockFib for
	 *            fibs that need some more complex processing
	 */
	public static void register(ServerWorld world, Block block, BlockFib fib) {
		FibLib.getInstance(world).fibs.put(block, fib);
		FibLib.ALL_FIB_BLOCKS.add(block);
		FibLib.log("Registered a BlockFib for %s in %s", block.getTranslationKey(), world.getDimension().getType().toString());
	}

	/**
	 * This function is useful for registering Fibs before a world is available, i.e., in your ModInitializer
	 * @param dimensionType the dimension whose world we will eventually register our fibs in
	 * @param block the block to be fibbed, @see dev.hephaestus.fiblib.FibLib#register()
	 * @param fib the fib to register, @see dev.hephaestus.fiblib.FibLib#register()
	 */
	public static void register(DimensionType dimensionType, Block block, BlockFib fib) {
		PRE_LOAD.putIfAbsent(dimensionType, new ArrayList<>());
		PRE_LOAD.get(dimensionType).add(new Pair<>(block, fib));
		FibLib.ALL_FIB_BLOCKS.add(block);
		FibLib.log("Pre-loaded a BlockFib for %s in %s", block.getTranslationKey(), dimensionType.toString());
	}

	/**
	 * A convenience function so that we can get get() without an instance.
	 * @param state the state of the block we're inquiring about. Note that because this is passed to a BlockFib, other
	 *              aspects of the state than the Block may be used in determining the output
	 * @param player the player who we will be fibbing to
	 * @return the result of the fib. This is what the player will get told the block is
	 */
	@Internal
	public static BlockState get(BlockState state, ServerPlayerEntity player) {
		try {
			return FibLib.getInstance(player).getWithInstance(state, player);
		} catch(NullPointerException e) {
			return state;
		}
	}


	/**
	 * Begins tracking a block for updates. Any time a Fibber that applies to this block checks its conditions, it will
	 * be against this entry. Automatically called on block add,see dev.hephaestus.fiblib.mixin.BlockMixin
	 * @param world the world that this block is in
	 * @param block the block we care about. used for selective updating
	 * @param pos the position of the block we are going to keep track of
	 */
	public static void put(ServerWorld world, Block block, BlockPos pos) {
		FibLib.getInstance(world).putWithInstance(block, pos);
	}

	/**
	 * @param world the world that this block is in
	 * @param state the block state we care about; note that only the actual Block is used, state info is disregarded
	 * @param pos the position of the block we are going to keep track of
	 */
	public static void put(ServerWorld world, BlockState state, BlockPos pos) {
		FibLib.put(world, state.getBlock(), pos);
	}

	/**
	 * This method is awful. If you use it, you are bad and should feel bad. This method is so awful, it has a body
	 * count higher than I am legally allowed to write here. If it was any *more* awful, it would probably have been
	 * elected the 45th President of the United States. That being said, it totally works, even if it makes me cry.
	 * @param block the block we care about. used for selective updating
	 * @param pos the position of the block we are going to keep track of
	 */
	public static void put(Block block, BlockPos pos) {
		if (ALL_FIB_BLOCKS.contains(block)) {
			PENDING_OVERWORLD_BLOCKS.add(new Pair<>(block, pos));
		}
	}


	/**
	 * Removes a block from tracking. Automatically called on block removal, see dev.hephaestus.fiblib.mixin.BlockMixin
	 * @param world the world that this block is in
	 * @param pos the position of the block we are going to keep track of
	 */
	public static void remove(ServerWorld world, BlockPos pos) {
		FibLib.getInstance(world).removeWithInstance(world, pos);
	}
}
