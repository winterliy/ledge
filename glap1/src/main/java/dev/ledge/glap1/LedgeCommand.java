package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

final class LedgeCommand implements CommandExecutor, TabCompleter {
    private final Pregenerator chunky;

    LedgeCommand(Pregenerator chunky) {
        this.chunky = chunky;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equalsIgnoreCase("pregen")) return false;
        if (args[1].equalsIgnoreCase("status") && args.length == 2) {
            sender.sendMessage(chunky.status());
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (action.equals("status")) {
            sender.sendMessage(chunky.status());
            return true;
        }
        if (action.equals("start")) {
            // /ledge pregen start <world> <radius> [square|circle] [centerX centerZ]
            if (args.length != 4 && args.length != 5 && args.length != 7) return false;
            double radius;
            Double centerX = null;
            Double centerZ = null;
            try {
                radius = Double.parseDouble(args[3]);
                if (args.length == 7) {
                    centerX = Double.parseDouble(args[5]);
                    centerZ = Double.parseDouble(args[6]);
                }
            } catch (NumberFormatException exception) {
                sender.sendMessage("Radius and center must be numbers.");
                return true;
            }
            sender.sendMessage(chunky.start(args[2], radius, args.length >= 5 ? args[4] : "square", centerX, centerZ));
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
        if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) return List.of("start", "pause", "continue", "cancel", "status");
        if (args.length == 3 && args[0].equalsIgnoreCase("pregen")) {
            return Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList();
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("start")) return List.of("512", "1024", "2048");
        if (args.length == 5 && args[1].equalsIgnoreCase("start")) return List.of("square", "circle");
        return List.of();
    }
}
