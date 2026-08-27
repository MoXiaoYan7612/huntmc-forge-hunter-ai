package cn.huntmc.hunterai.monitor;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/** Stable local pseudonyms for players; the secret and raw UUIDs never enter replay output. */
final class PlayerAliasService {
  private static final Logger LOG = LogUtils.getLogger();
  private static final int KEY_BYTES = 32;
  private static final int ALIAS_BYTES = 12;
  private final byte[] key;

  static PlayerAliasService loadDefault() {
    Path path = FMLPaths.CONFIGDIR.get().resolve("huntmc-hunter-ai-monitor.key");
    try {
      Files.createDirectories(path.getParent());
      if (Files.isRegularFile(path)) {
        byte[] existing = Base64.getDecoder().decode(Files.readString(path, StandardCharsets.US_ASCII).trim());
        if (existing.length >= KEY_BYTES) return new PlayerAliasService(existing);
        throw new IOException("monitor alias key is shorter than " + KEY_BYTES + " bytes");
      }
      byte[] generated = randomKey();
      Files.writeString(
          path,
          Base64.getEncoder().encodeToString(generated) + "\n",
          StandardCharsets.US_ASCII,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return new PlayerAliasService(generated);
    } catch (IOException | IllegalArgumentException exception) {
      LOG.error(
          "Unable to load the persistent Hunter monitor alias key. This server run will use ephemeral aliases.",
          exception);
      return new PlayerAliasService(randomKey());
    }
  }

  PlayerAliasService(byte[] key) {
    if (key == null || key.length < KEY_BYTES)
      throw new IllegalArgumentException("player alias key is too short");
    this.key = Arrays.copyOf(key, key.length);
  }

  String alias(UUID playerId) {
    if (playerId == null) throw new IllegalArgumentException("player ID is required");
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      byte[] digest =
          mac.doFinal(playerId.toString().getBytes(StandardCharsets.US_ASCII));
      return "player-" + HexFormat.of().formatHex(digest, 0, ALIAS_BYTES);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    }
  }

  private static byte[] randomKey() {
    byte[] key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key);
    return key;
  }
}
