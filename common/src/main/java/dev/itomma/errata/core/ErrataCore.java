package dev.itomma.errata.core;

import net.minecraft.SharedConstants;
import net.minecraft.server.packs.PackType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single seam between the loader-agnostic core and whatever is hosting it.
 *
 * <p>Everything in {@code dev.itomma.errata.core} compiles against vanilla Minecraft, SLF4J and
 * GSON only. A loader entrypoint calls {@link #bind} once during startup and the core then has
 * everything it needs.
 */
public final class ErrataCore {

    public static final String MOD_ID = "errata";
    public static final Logger LOGGER = LoggerFactory.getLogger("Errata");

    /**
     * Data pack format in which {@code item} and {@code tag} item-predicate keys were replaced by
     * a single {@code items} field -- that is, 1.20.5.
     *
     * <p>Measured, not guessed: 1.20.1 is 15, 1.20.4 is 26, 1.20.6 is 41, 1.21.1 is 48. The
     * predicate change lands exactly on the 26 to 41 boundary, which makes the pack format a
     * more reliable signal than the version string. It also survives the 2026 switch to
     * year-based version numbers, where string parsing would not.
     */
    public static final int FIRST_MODERN_PREDICATE_PACK_FORMAT = 41;

    private static volatile ErrataConfig config = ErrataConfig.DEFAULTS;
    private static volatile int packFormat = -1;

    private ErrataCore() {
    }

    /** @param cfg live config view for this loader */
    public static void bind(ErrataConfig cfg) {
        if (cfg != null) {
            config = cfg;
        }
    }

    public static ErrataConfig config() {
        return config;
    }

    /**
     * The running game's data pack format, read from the game itself.
     *
     * <p>Deliberately not a per-loader constant. {@link SharedConstants#getCurrentVersion()} has
     * existed unchanged from 1.20.1 through 26.2, so asking the game removes a number that a
     * version port would otherwise have to remember to update -- and that, if forgotten, would
     * silently mis-stamp the generated pack and mis-fire the repair guard.
     *
     * <p>Resolved lazily rather than at {@link #bind} time so mod construction never depends on
     * how early the loader initialises {@code SharedConstants}.
     */
    public static int packFormat() {
        int cached = packFormat;
        if (cached > 0) {
            return cached;
        }
        int resolved;
        try {
            resolved = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
        } catch (Throwable t) {
            // Should not happen on a running server. Fall back to the oldest format that is
            // still safe to repair, so a failure here disables the repair rather than
            // performing it against unknown rules.
            LOGGER.warn("Could not read the data pack format from the game ({}); "
                    + "assuming a pre-1.20.5 pack and skipping advancement repair.", t.toString());
            resolved = 1;
        }
        packFormat = resolved;
        return resolved;
    }

    /**
     * Whether this game version uses the 1.20.5+ item predicate format, and therefore whether
     * the advancement repair is meaningful here.
     *
     * <p>On 1.20.4 and earlier, {@code {"tag": "x"}} is <em>correct</em>, not stale. Repairing it
     * would rewrite a working advancement into one the game cannot read. See
     * {@link AdvancementRepair} for the full history.
     */
    public static boolean usesModernItemPredicates() {
        return packFormat() >= FIRST_MODERN_PREDICATE_PACK_FORMAT;
    }
}
