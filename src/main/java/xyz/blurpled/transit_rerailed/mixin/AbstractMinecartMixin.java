package xyz.blurpled.transit_rerailed.mixin;

import com.mojang.datafixers.util.Pair;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PoweredRailBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.enums.RailShape;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import xyz.blurpled.transit_rerailed.TransitRerailed;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.sql.Timestamp;

/**
 * Mixin which overrides the default minecart behavior so that we can define a
 * changeable speed, and check for updates.
 */
@Mixin(AbstractMinecartEntity.class)
public abstract class AbstractMinecartMixin extends Entity {
	private static final double DEFAULT_SPEED = TransitRerailed.config.getDefaultSpeed();
	private static final double MIN_SPEED = TransitRerailed.config.getMinimumSpeed();
	private static final double MAX_SPEED = TransitRerailed.config.getMaximumSpeed();
	private static final Pattern SIGN_PATTERN = Pattern.compile(TransitRerailed.config.getSignRegex());
	private static final boolean DEBUG_TOGGLE = TransitRerailed.config.getDebugToggle();
	private static final boolean SPEEDOMETER_TOGGLE = TransitRerailed.config.getSpeedometerToggle();
	private static final String SPEEDOMETER_UNITS = TransitRerailed.config.getSpeedometerUnits();
	private static final double CUSTOM_UNITS = TransitRerailed.config.getCustomUnits();
	private static final double tickSlowdownValue = 10;//Speedcarts.Config.getTickSlowdown();

	/**
	 * The standard Fabric Logger
	 */
	private static final Logger LOGGER = TransitRerailed.LOGGER;

	/**
	 * Time in game ticks, to wait before attempting to update the cart's speed
	 * from the same position, after that block/sign has already updated the
	 * cart's speed just before.
	 */
	private static final long SPEED_UPDATE_COOLDOWN = 20 * 3;

	/**
	 * The tick function cooldown, so it doesn't drag the server and/or client down too much when many minecarts are in the world moving around
	 */
	private static double tickSlowdownTimer = 0;

	/**
	 * The currently-set maximum speed, in blocks per second.
	 */
	private double maxSpeedBps = DEFAULT_SPEED;

	/**
	 * The last time (in game ticks) that the speed was updated.
	 */
	private long lastSpeedUpdate = 0;

	/**
	 * The position of the block which was last responsible for updating the
	 * cart's speed.
	 */
	private BlockPos lastUpdatedFrom = null;

	/**
	 * Timestamp, for debug reasons.
	 */
	Timestamp timestamp = new Timestamp(System.currentTimeMillis());

	private void outDebugLogs(String input) {
		if (DEBUG_TOGGLE) {
			LOGGER.info(input);
		}
	}

	@Shadow
	public abstract Vec3d snapPositionToRail(double x, double y, double z);

	@Shadow
	private static Pair<Vec3i, Vec3i> getAdjacentRailPositionsByShape(RailShape shape) {
		return null;
	}

	@Shadow
	protected abstract double getMaxSpeed();

	@Inject(at = @At("HEAD"), method = "getMaxSpeed", cancellable = true)
	public void getMaxOffRailSpeedOverwrite(CallbackInfoReturnable<Double> cir) {
		cir.setReturnValue(this.maxSpeedBps / 20.0);
	}

	@Shadow
	protected abstract void applySlowdown();

	@Shadow
	protected abstract boolean willHitBlockAt(BlockPos pos);

	public AbstractMinecartMixin(EntityType<?> type, World world) {
		super(type, world);
	}

	@Shadow
	protected abstract void moveOnRail(BlockPos pos, BlockState state);

	@Shadow
	public abstract Direction getMovementDirection();

	@Inject(at = @At("HEAD"), method = "moveOnRail", cancellable = true)
	public void moveOnRailOverwrite(BlockPos pos, BlockState state, CallbackInfo ci) throws InterruptedException {
		this.updateForSpeedModifiers(pos);
		this.modifiedMoveOnRail(pos, state);
		ci.cancel();
	}

	/**
	 * Runs every tick, handles the speedometer and slowing sounds when signs *are not* present.
	 */
	@Inject(at = @At("INVOKE"), method = "tick")
	public void tick(CallbackInfo ci) {
		tickSlowdownTimer = tickSlowdownTimer + 1;
		if (tickSlowdownTimer >= tickSlowdownValue) {
			double unitConversion;
			if (SPEEDOMETER_UNITS.equals("KPH")) {
				unitConversion = 3.6;
			} else if (SPEEDOMETER_UNITS.equals("MPH")) {
				unitConversion = 2.23694;
			} else {
				unitConversion = CUSTOM_UNITS;
			}
			double currentSpeed = this.getVelocity().length() * 4;
			double unitOutput = (this.getVelocity().length() * 4) * unitConversion;
			if (SPEEDOMETER_TOGGLE && this.getFirstPassenger() instanceof PlayerEntity player) {
				player.sendMessage(Text.of(
						"MPS: " + String.format("%.2f", currentSpeed) + " / " + 				// Current speed in MPS
						SPEEDOMETER_UNITS + ": " + String.format("%.2f", unitOutput) + " / " + 	// Current speed in the server selected unit
						// "\n" + 																// Line break, but doesn't work
						"Current Limit: " + String.format("%.2f", this.maxSpeedBps) + " / " +  	// Current speed limit
						"Global Limit: " + String.format("%.2f",MAX_SPEED) 						// Global speed limit
				), true);
			}
			tickSlowdownTimer = 0;
		}
	}


	/**
	 * Checks for any speed modifiers (signs, blocks, etc.) and attempts to
	 * apply their effects to the cart. It does this by iterating over a list of
	 * possible positions for blocks which are considered speed modifiers, and
	 * then if a valid speed modifier is found, the cart's speed is updated.
	 * @param pos The cart's current position.
	 */
	private void updateForSpeedModifiers(BlockPos pos) {
		// Quit if the cart is not moving, and set its speed to default.
		if (this.getVelocity().length() <= 0.5) {
			this.maxSpeedBps = DEFAULT_SPEED;
			return;
		}

		for (BlockPos position : this.getPositionsToCheck(pos)) {
			BlockEntity blockEntity = this.world.getBlockEntity(position);
			if (blockEntity instanceof SignBlockEntity sign) {
				if (!sign.getPos().equals(this.lastUpdatedFrom) || this.world.getTime() > this.lastSpeedUpdate + SPEED_UPDATE_COOLDOWN) {
					BlockState state = this.world.getBlockState(position);
					Direction dir = (Direction) state.getEntries().get(Properties.HORIZONTAL_FACING);
					// Only allow freestanding signs or those facing the cart.
					if (dir == null || dir.equals(this.getMovementDirection().getOpposite())) {
						if (this.updateSpeedForSign(sign)) return;
					}
				}
			}
		}
	}

	/**
	 * Attempts to update the cart's speed according to a sign.
	 * @param sign The sign that contains speed information.
	 * @return True if the cart's speed was updated, or false otherwise.
	 * Also handles Sounds when signs *are* present.
	 */
	private boolean updateSpeedForSign(SignBlockEntity sign) {
		Text text1 = sign.getTextOnRow(0, false);
		String SignContents = text1.getString();
		PlayerEntity player = null;
		if (this.hasPlayerRider()) {
			player = (PlayerEntity) this.getFirstPassenger();
		}
		try {
			double speed = Double.parseDouble(SignContents);
			if (speed >= MIN_SPEED && speed <= MAX_SPEED) {
				this.maxSpeedBps = speed;
				this.lastSpeedUpdate = this.world.getTime();
				this.lastUpdatedFrom = sign.getPos();

				if ((this.getVelocity().length() * 20) + 2 < speed && player != null) {
					// Accelerating / Speeding Up
					player.playSound(SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 1.0f, 1.0f);
				} else if ((this.getVelocity().length() * 20) + 2 > speed && player != null) {
					// Breaking / Slowing Down
					player.playSound(SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, SoundCategory.PLAYERS, 1.0f, 1.0f);
				}
				return true;
			} else {
				sign.setTextOnRow(0, Text.of("Invalid speed!"));
				sign.setTextOnRow(1, Text.of("Min: " + MIN_SPEED));
				sign.setTextOnRow(2, Text.of("Max: " + MAX_SPEED));
				sign.setGlowingText(true);
				sign.setTextColor(DyeColor.RED);
			}
		} catch (NumberFormatException e) {
			// Do nothing if no value could be parsed.
		}
		return false;
	}

	/**
	 * Gathers a list of all block positions to check for signs that may affect
	 * the cart's speed.
	 * @param pos The cart's block position.
	 * @return A collection of positions to check.
	 */
	private Collection<BlockPos> getPositionsToCheck(BlockPos pos) {
		// Compute the number of blocks we have to check ahead.
		// This accounts for speeds greater than 1 block per tick.
		int blockRange = Math.max(1, (int) Math.ceil(this.maxSpeedBps / 20));
		List<BlockPos> positionsToCheck = new ArrayList<>(6 * blockRange);
		for (int i = 0; i < blockRange; i++) {
			positionsToCheck.add(pos.north());
			positionsToCheck.add(pos.south());
			positionsToCheck.add(pos.east());
			positionsToCheck.add(pos.west());
			positionsToCheck.add(pos.up());
			positionsToCheck.add(pos.down());
			pos = pos.add(this.getMovementDirection().getVector());
		}
		return positionsToCheck;
	}

	/**
	 * Modified version of {@link AbstractMinecartMixin#moveOnRail(BlockPos, BlockState)}
	 * that allows the minecart to maintain speeds above 32 m/s.
	 * @param pos The block position of the cart.
	 * @param state The state of the block the cart is in.
	 */
	private void modifiedMoveOnRail(BlockPos pos, BlockState state) throws InterruptedException {
		this.fallDistance = 0.0F;
		double d = this.getX();
		double e = this.getY();
		double f = this.getZ();
		Vec3d vec3d = this.snapPositionToRail(d, e, f);
		e = pos.getY();
		boolean onPoweredRail = false;
		boolean onNormalRail = false;
		if (state.isOf(Blocks.POWERED_RAIL)) {
			onPoweredRail = state.get(PoweredRailBlock.POWERED);
			onNormalRail = !onPoweredRail;
		}

		double g = 0.0078125D;
		if (this.isTouchingWater()) {
			g *= 0.2D;
		}

		Vec3d velocity = this.getVelocity();
		RailShape railShape = state.get(((AbstractRailBlock)state.getBlock()).getShapeProperty());
		switch (railShape) {
			case ASCENDING_EAST -> {
				this.setVelocity(velocity.add(-g, 0.0D, 0.0D));
				++e;
			}
			case ASCENDING_WEST -> {
				this.setVelocity(velocity.add(g, 0.0D, 0.0D));
				++e;
			}
			case ASCENDING_NORTH -> {
				this.setVelocity(velocity.add(0.0D, 0.0D, g));
				++e;
			}
			case ASCENDING_SOUTH -> {
				this.setVelocity(velocity.add(0.0D, 0.0D, -g));
				++e;
			}
		}

		velocity = this.getVelocity();
		Pair<Vec3i, Vec3i> adjacentRailPositions = getAdjacentRailPositionsByShape(railShape);
		Vec3i vec3i = adjacentRailPositions.getFirst(); // a "try" arg might be a possible fix for this error
		Vec3i vec3i2 = adjacentRailPositions.getSecond();
		double h = vec3i2.getX() - vec3i.getX();
		double i = vec3i2.getZ() - vec3i.getZ();
		double j = Math.sqrt(h * h + i * i);
		double k = velocity.x * h + velocity.z * i;
		if (k < 0.0D) {
			h = -h;
			i = -i;
		}

		double l = Math.min(2.0D, velocity.horizontalLength());
		// Only consider using Minecraft's default velocity damper logic when going at 'normal' speeds.
		if (this.maxSpeedBps <= DEFAULT_SPEED) {
			this.setVelocity(new Vec3d(l * h / j, velocity.y, l * i / j));
		} else { // Otherwise, simply clamp to the computed max speed in blocks per tick.
			double speed = this.maxSpeedBps / 20.0;
			this.setVelocity(new Vec3d(
					Math.max(Math.min(speed, velocity.x), -speed),
					velocity.y,
					Math.max(Math.min(speed, velocity.z), -speed)
			));
		}

		Entity entity = this.getFirstPassenger();
		if (entity instanceof PlayerEntity) {
			Vec3d playerVelocity = entity.getVelocity();
			double m = playerVelocity.horizontalLengthSquared();
			double n = this.getVelocity().horizontalLengthSquared();
			if (m > 1.0E-4D && n < 0.01D) {
				this.setVelocity(this.getVelocity().add(playerVelocity.x * 0.1D, 0.0D, playerVelocity.z * 0.1D));
				onNormalRail = false;
			}
		}

		double p;
		if (onNormalRail) {
			p = this.getVelocity().horizontalLength();
			if (p < 0.03D) {
				this.setVelocity(Vec3d.ZERO);
			} else {
				this.setVelocity(this.getVelocity().multiply(0.5D, 0.0D, 0.5D));
			}
		}

		p = (double)pos.getX() + 0.5D + (double)vec3i.getX() * 0.5D;
		double q = (double)pos.getZ() + 0.5D + (double)vec3i.getZ() * 0.5D;
		double r = (double)pos.getX() + 0.5D + (double)vec3i2.getX() * 0.5D;
		double s = (double)pos.getZ() + 0.5D + (double)vec3i2.getZ() * 0.5D;
		h = r - p;
		i = s - q;
		double x;
		double v;
		double w;
		if (h == 0.0D) {
			x = f - (double)pos.getZ();
		} else if (i == 0.0D) {
			x = d - (double)pos.getX();
		} else {
			v = d - p;
			w = f - q;
			x = (v * h + w * i) * 2.0D;
		}

		d = p + h * x;
		f = q + i * x;
		this.setPosition(d, e, f);
		v = this.hasPassengers() ? 0.75D : 1.0D;
		w = this.getMaxSpeed();
		velocity = this.getVelocity();
		Vec3d movement = new Vec3d(MathHelper.clamp(v * velocity.x, -w, w), 0.0D, MathHelper.clamp(v * velocity.z, -w, w));
		this.move(MovementType.SELF, movement);
		if (vec3i.getY() != 0 && MathHelper.floor(this.getX()) - pos.getX() == vec3i.getX() && MathHelper.floor(this.getZ()) - pos.getZ() == vec3i.getZ()) {
			this.setPosition(this.getX(), this.getY() + (double)vec3i.getY(), this.getZ());
		} else if (vec3i2.getY() != 0 && MathHelper.floor(this.getX()) - pos.getX() == vec3i2.getX() && MathHelper.floor(this.getZ()) - pos.getZ() == vec3i2.getZ()) {
			this.setPosition(this.getX(), this.getY() + (double)vec3i2.getY(), this.getZ());
		}

		this.applySlowdown();
		Vec3d vec3d4 = this.snapPositionToRail(this.getX(), this.getY(), this.getZ());
		Vec3d vec3d7;
		double af;
		if (vec3d4 != null && vec3d != null) {
			double aa = (vec3d.y - vec3d4.y) * 0.05D;
			vec3d7 = this.getVelocity();
			af = vec3d7.horizontalLength();
			if (af > 0.0D) {
				this.setVelocity(vec3d7.multiply((af + aa) / af, 1.0D, (af + aa) / af));
			}

			this.setPosition(this.getX(), vec3d4.y, this.getZ());
		}

		int ac = MathHelper.floor(this.getX());
		int ad = MathHelper.floor(this.getZ());
		if (ac != pos.getX() || ad != pos.getZ()) {
			vec3d7 = this.getVelocity();
			af = vec3d7.horizontalLength();
			this.setVelocity(af * (double)(ac - pos.getX()), vec3d7.y, af * (double)(ad - pos.getZ()));
		}

		if (onPoweredRail) {
			vec3d7 = this.getVelocity();
			af = vec3d7.horizontalLength();

			if (af > 0.01D) {
				this.setVelocity(vec3d7.add(vec3d7.x / af * 0.06D, 0.0D, vec3d7.z / af * 0.06D));
			} else {
				Vec3d vec3d8 = this.getVelocity();
				double ah = vec3d8.x;
				double ai = vec3d8.z;
				if (railShape == RailShape.EAST_WEST) {
					if (this.willHitBlockAt(pos.west())) {
						ah = 0.02D;
					} else if (this.willHitBlockAt(pos.east())) {
						ah = -0.02D;
					}
				} else {
					if (railShape != RailShape.NORTH_SOUTH) {
						return;
					}

					if (this.willHitBlockAt(pos.north())) {
						ai = 0.02D;
					} else if (this.willHitBlockAt(pos.south())) {
						ai = -0.02D;
					}
				}

				this.setVelocity(ah, vec3d8.y, ai);
			}
		}
	}
}
