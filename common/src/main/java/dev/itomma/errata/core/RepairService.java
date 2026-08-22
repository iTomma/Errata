package dev.itomma.errata.core;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs {@link AdvancementRepair} and gets the resulting datapack live.
 *
 * <p>This used to sit in the NeoForge entrypoint, but none of it is loader-specific: it is
 * vanilla {@code PackRepository} plumbing. Keeping it here means a Fabric entrypoint only has to
 * call {@link #run} and print {@link Outcome#message()}.
 */
public final class RepairService {

    /**
     * @param repaired how many advancements were rewritten
     * @param message  a human-readable summary, suitable for chat feedback or a log line
     * @param reloaded whether a resource reload was triggered as a result
     */
    public record Outcome(int repaired, String message, boolean reloaded) {
    }

    private RepairService() {
    }

    public static Outcome run(MinecraftServer server) {
        // Refuse to run where the repair would be wrong rather than merely unnecessary.
        //
        // On 1.20.4 and earlier, {"tag": "x"} is the correct way to write an item predicate --
        // not a stale one. repairPredicate() only skips predicates carrying items/count/
        // components/predicates, none of which an old-format predicate has, so without this
        // guard every working advancement on those versions would be rewritten into something
        // the game cannot read. That is the exact failure this mod exists to prevent.
        if (!ErrataCore.usesModernItemPredicates()) {
            String msg = "Advancement repair does not apply to this Minecraft version "
                    + "(data pack format " + ErrataCore.packFormat() + "; the item predicate "
                    + "format this repairs arrived in " + ErrataCore.FIRST_MODERN_PREDICATE_PACK_FORMAT
                    + ", i.e. 1.20.5). Nothing was changed.";
            ErrataCore.LOGGER.info(msg);
            return new Outcome(0, msg, false);
        }

        AdvancementRepair.Result result;
        try {
            result = AdvancementRepair.run(server);
        } catch (Throwable t) {
            String msg = "Advancement repair failed: " + t;
            ErrataCore.LOGGER.warn(msg);
            return new Outcome(0, msg, false);
        }

        if (result.repaired() == 0) {
            String msg = "No broken recipe advancements found.";
            ErrataCore.LOGGER.info(msg);
            return new Outcome(0, msg, false);
        }

        String msg = "Repaired " + result.repaired() + " advancement(s): "
                + String.join(", ", result.recipes());
        ErrataCore.LOGGER.info("Repaired {} recipe advancement(s) that would have unlocked on any "
                + "pickup: {}", result.repaired(), String.join(", ", result.recipes()));

        if (!result.changed()) {
            // Already written on a previous run; the pack is live.
            return new Outcome(result.repaired(), msg, false);
        }

        try {
            PackRepository repo = server.getPackRepository();
            repo.reload();
            List<String> selected = new ArrayList<>(repo.getSelectedIds());
            String id = "file/" + AdvancementRepair.PACK_DIR;
            if (repo.getAvailableIds().contains(id) && !selected.contains(id)) {
                selected.add(id);
            }
            ErrataCore.LOGGER.info("Enabling repair datapack and reloading.");
            server.reloadResources(selected);
            return new Outcome(result.repaired(), msg, true);
        } catch (Throwable t) {
            ErrataCore.LOGGER.warn("Wrote the repairs but could not reload automatically ({}). "
                    + "Run /reload to apply them.", t.toString());
            return new Outcome(result.repaired(),
                    msg + " (could not reload automatically -- run /reload)", false);
        }
    }
}
