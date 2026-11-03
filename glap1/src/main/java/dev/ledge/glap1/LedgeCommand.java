package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

final class LedgeCommand implements CommandExecutor, TabCompleter {
    private final ChunkyBridge chunky;

    LedgeCommand(ChunkyBridge chunky) {
        this.chunky = chunky;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("pregen")) return false;
        if (chunky == null) {
            sender.sendMessage("Chunky is not installed or its API is unavailable.");
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("start")) {
            if (args.length != 4) return false;
            double radius;
            try {
                radius = Double.parseDouble(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage("Radius must be a number.");
                return true;
            }
            sender.sendMessage(chunky.start(args[2], radius));
        } else {
            if (args.length != 3) return false;
            String result = switch (action) {
                case "pause" -> chunky.pause(args[2]);
                case "continue", "resume" -> chunky.resume(args[2]);
                case "cancel" -> chunky.cancel(args[2]);
                default -> null;
            };
            if (result == null) return false;
            sender.sendMessage(result);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("pregen");
        if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) return List.of("start", "pause", "continue", "cancel");
        if (args.length == 3 && args[0].equalsIgnoreCase("pregen")) {
            return Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList();
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("start")) return List.of("512", "1024", "2048");
        return List.of();
    }
}
