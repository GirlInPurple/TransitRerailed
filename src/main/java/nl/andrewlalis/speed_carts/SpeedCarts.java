package nl.andrewlalis.speed_carts;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpeedCarts implements ModInitializer {
	public static Config config;

	public static final Logger LOGGER = LoggerFactory.getLogger("SpeedCarts");

	@Override
	public void onInitialize() {
		config = new Config();
		LOGGER.info("SpeedCarts Init! Happy Zooming!");
		LOGGER.info("All future console logs from this mod are debug messages.");
	}
}
