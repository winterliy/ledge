package dev.ledge.glap1;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.BindException;
import java.util.logging.Level;

public final class LedgeGlap1Plugin extends JavaPlugin {
    private MapTileEngine tileEngine;
    private LedgeMapHttpServer webServer;
    private LedgeFabricBridge fabricBridge;

    private BuiltInPregenerator builtInPregenerator;
    private TaskHandle engineTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tileEngine = new MapTileEngine(this);
        fabricBridge = new LedgeFabricBridge(this, tileEngine);
        fabricBridge.register();
        engineTask = Schedulers.globalTimer(this, tileEngine, 1L, 1L);
        Bukkit.getPluginManager().registerEvents(new LedgeWorldListener(tileEngine), this);
        builtInPregenerator = new BuiltInPregenerator(this, tileEngine);
        Pregenerator pregenerator = builtInPregenerator;
        if (getCommand("ledge") != null) {
            LedgeCommand command = new LedgeCommand(pregenerator);
            getCommand("ledge").setExecutor(command);
            getCommand("ledge").setTabCompleter(command);
        }
        webServer = new LedgeMapHttpServer(this, tileEngine);
        try {
            webServer.start();
        } catch (BindException exception) {
            getLogger().log(Level.SEVERE, "Ledge web map port is already in use. Change web.port in config.yml.", exception);
        } catch (IOException exception) {
            getLogger().log(Level.SEVERE, "Could not start Ledge web map; tile generation remains available.", exception);
        }
        getLogger().info("Ledge GLAP1 adapter is enabled.");
    }

    @Override
    public void onDisable() {
        if (engineTask != null) engineTask.cancel();
        if (builtInPregenerator != null) builtInPregenerator.shutdown();
        if (webServer != null) webServer.stop();
        if (fabricBridge != null) fabricBridge.unregister();
        if (tileEngine != null) tileEngine.shutdown();
    }
}
