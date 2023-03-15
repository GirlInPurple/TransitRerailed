package xyz.blurpled.transit_rerailed;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransitRerailed implements ModInitializer {
	public static Config config;

	public static final Logger LOGGER = LoggerFactory.getLogger("TransitRerailed");

	@Override
	public void onInitialize() {
		config = new Config();
		LOGGER.info("TransitRerailed Init! Happy Zooming!");
		LOGGER.info("All future console logs from this mod are debug messages.");
	}
}
