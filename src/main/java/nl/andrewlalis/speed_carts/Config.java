package nl.andrewlalis.speed_carts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Contains all configuration options and the logic for loading config.
 */
public class Config {
	private static final Path CONFIG_FILE = Paths.get("config", "speed_carts.yaml");
	private final String configVersion;
	private final double defaultSpeed;
	private final double minimumSpeed;
	private final double maximumSpeed;
	private final String signRegex;
	private final double stationTime;
	private static boolean debugToggle = false;
	private final boolean speedometerToggle;
	private final String speedometerUnits;
	private final List<String> validConfigVersions = Arrays.asList("0.0.1", "0.0.2");
	private static final Logger LOGGER = SpeedCarts.LOGGER;


	public Config() {
		try {
			this.ensureConfigValid();
			ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
			JsonNode configJson = mapper.readTree(Files.newInputStream(CONFIG_FILE));
			this.configVersion = configJson.get("configVersion").asText();
			this.defaultSpeed = configJson.get("defaultSpeed").asDouble();
			this.minimumSpeed = configJson.get("minimumSpeed").asDouble();
			this.maximumSpeed = configJson.get("maximumSpeed").asDouble();
			this.signRegex = configJson.get("signRegex").asText();
			this.stationTime = configJson.get("stationTime").asDouble();
			this.debugToggle = configJson.get("debugToggle").asBoolean();
			this.speedometerToggle = configJson.get("speedometerToggle").asBoolean();
			this.speedometerUnits = configJson.get("speedometerUnits").asText();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void ensureConfigValid() throws IOException {
		while (true) {
			if (!Files.exists(CONFIG_FILE)) {
				Files.createDirectories(CONFIG_FILE.getParent());
				OutputStream out = Files.newOutputStream(CONFIG_FILE);
				InputStream defaultConfigInputStream = SpeedCarts.class.getClassLoader().getResourceAsStream("default_config.yaml");
				if (defaultConfigInputStream == null) {
					throw new IOException("Could not load default_config.yaml");
				}
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = defaultConfigInputStream.read(buffer)) > 0) {
					out.write(buffer, 0, bytesRead);
				}
				defaultConfigInputStream.close();
				out.close();
				break;
			} else if (!validConfigVersions.contains(configVersion)) {
				LOGGER.info("Updated config version and configs have been wiped. Please shut down the server and check the configs.");
				Files.delete(CONFIG_FILE);
			} else {
				break;
			}
		}
	}

	public double getDefaultSpeed() {
		return defaultSpeed;
	}

	public double getMinimumSpeed() {
		return minimumSpeed;
	}

	public double getMaximumSpeed() {
		return maximumSpeed;
	}

	public String getSignRegex() {
		return signRegex;
	}

	public Double getStationTime() {
		return stationTime;
	}

	public boolean getDebugToggle(){
		return debugToggle;
	}
}
