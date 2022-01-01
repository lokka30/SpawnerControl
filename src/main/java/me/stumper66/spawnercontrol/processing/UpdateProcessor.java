package me.stumper66.spawnercontrol.processing;

import me.stumper66.spawnercontrol.DebugType;
import me.stumper66.spawnercontrol.SpawnerControl;
import me.stumper66.spawnercontrol.SpawnerInfo;
import me.stumper66.spawnercontrol.Utils;
import me.stumper66.spawnercontrol.WorldGuardManager;
import org.bukkit.block.CreatureSpawner;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

public class UpdateProcessor {
    public UpdateProcessor(final @NotNull SpawnerProcessor sp){
        this.sp = sp;
        this.main = sp.main;
        this.spawnerUpdateQueue = new ConcurrentLinkedDeque<>();
        this.allSpawners = new HashMap<>();
        this.chunkMappings = new HashMap<>();
        this.worldMappings = new HashMap<>();
    }

    private final SpawnerControl main;
    final @NotNull Map<BasicLocation, CreatureSpawner> allSpawners;
    final @NotNull Map<Long, Set<BasicLocation>> chunkMappings;
    final @NotNull Map<String, Set<BasicLocation>> worldMappings;
    private final SpawnerProcessor sp;
    final @NotNull Queue<SpawnerUpdateInterface> spawnerUpdateQueue;
    private boolean recheckCriteria;
    final static Object chunkLock = new Object();

    void processUpdates() {
        if (recheckCriteria) recheckSpawnerCriteria();

        while (true){
            final SpawnerUpdateInterface itemInterface = this.spawnerUpdateQueue.poll();

            if (itemInterface == null) return;

            switch (itemInterface.getOperation()){
                case REMOVE:
                    removeSpawner((SpawnerUpdateItem) itemInterface);
                    break;
                case CHUNK_UNLOADED:
                case CHUNK_REFRESH:
                    processChunk((SpawnerChunkUpdate) itemInterface);
                    break;
                case CUSTOM_NAME_CHANGE:
                    processSpawnerRename((SpawnerUpdateItem) itemInterface);
                    break;
                default: // update and add
                    processSpawnerOrChunkAdd((SpawnerUpdateItem) itemInterface);
                    break;
            }
        }
    }

    private void processSpawnerRename(final @NotNull SpawnerUpdateItem item){
        final SpawnerInfo info = sp.activeSpawners.get(item.basicLocation);
        if (info != null){
            info.clearCache();
            info.setCs(item.cs);
        }

        evaluateTrackingCriteriaForSpawner(item.cs, item.basicLocation);
    }

    private void removeSpawner(final @NotNull SpawnerUpdateItem item){
        synchronized (SpawnerProcessor.lock_ActiveSpawners) {
            sp.activeSpawners.remove(item.basicLocation);
        }
        if (this.chunkMappings.containsKey(item.cs.getLocation().getChunk().getChunkKey()))
            this.chunkMappings.get(item.cs.getLocation().getChunk().getChunkKey()).remove(item.basicLocation);
        if (this.worldMappings.containsKey(item.cs.getLocation().getWorld().getName()))
            this.worldMappings.get(item.cs.getLocation().getWorld().getName()).remove(item.basicLocation);
    }

    private void processChunk(final @NotNull SpawnerChunkUpdate spawnerChunkUpdate){
        Set<BasicLocation> locations;

        synchronized (chunkLock) {
            if (!this.chunkMappings.containsKey(spawnerChunkUpdate.chunkId)) return;
            locations = this.chunkMappings.get(spawnerChunkUpdate.chunkId);
        }

        if (locations == null) return;

        for (final BasicLocation location : locations){
            if (spawnerChunkUpdate.getOperation() == UpdateOperation.CHUNK_REFRESH){
                final CreatureSpawner cs = this.allSpawners.get(location);
                if (cs == null) continue;

                evaluateTrackingCriteriaForSpawner(cs, location);
            }
            else {
                // chunk unloaded
                if (!sp.activeSpawners.containsKey(location)) continue;

                sp.activeSpawners.get(location).isChunkLoaded = false;
            }
        }
    }

    private void processSpawnerOrChunkAdd(final @NotNull SpawnerUpdateItem item){
        this.allSpawners.put(item.basicLocation, item.cs);

        evaluateTrackingCriteriaForSpawner(item.cs, item.basicLocation);
    }

    private void recheckSpawnerCriteria(){
        this.recheckCriteria = false;

        for (final BasicLocation basicLocation : this.allSpawners.keySet()){
            final CreatureSpawner cs = this.allSpawners.get(basicLocation);
            if (main.spawnerOptions.allowedWorlds.isEnabledInList(cs.getWorld().getName()))
                evaluateTrackingCriteriaForSpawner(cs, basicLocation);
        }
    }

    private void evaluateTrackingCriteriaForSpawner(final @NotNull CreatureSpawner cs, final @NotNull BasicLocation basicLocation){
        SpawnerInfo info = sp.activeSpawners.get(basicLocation);
        if (info == null)
            info = new SpawnerInfo(cs, sp.options);

        info.isChunkLoaded = cs.getLocation().isChunkLoaded();

        if (main.namedSpawnerOptions != null) {
            final String spawnerCustomName = info.getSpawnerCustomName(main);

            if (spawnerCustomName != null && main.namedSpawnerOptions.containsKey(spawnerCustomName))
                info.options = main.namedSpawnerOptions.get(spawnerCustomName);
            else
                info.options = main.spawnerOptions;
        }

        if (sp.hasWorldGuard)
            WorldGuardManager.updateWorlguardOptionsForSpawner(main, info);

        if (info.options == null)
            info.options = sp.options;

        final Set<BasicLocation> spawnersInChunk = this.chunkMappings.computeIfAbsent(cs.getLocation().getChunk().getChunkKey(), k -> new HashSet<>());
        spawnersInChunk.add(info.getBasicLocation());

        final Set<BasicLocation> spawnersInWorld = this.worldMappings.computeIfAbsent(cs.getLocation().getWorld().getName(), k -> new HashSet<>());
        spawnersInWorld.add(info.getBasicLocation());

        final String name = info.getSpawnerCustomName(main);

        if (info.options.allowedEntityTypes.isEnabledInList(cs.getSpawnedType())) {
            if (info.isChunkLoaded && !sp.activeSpawners.containsKey(info.getBasicLocation())) {
                if (main.debugInfo.doesSpawnerMeetDebugCriteria(main, DebugType.SPAWNER_ACTIVATION, info))
                    Utils.logger.info("now active: " + Utils.showSpawnerLocation(cs));
                synchronized (SpawnerProcessor.lock_ActiveSpawners) {
                    sp.activeSpawners.put(info.getBasicLocation(), info);
                }
            }
            else if (!info.isChunkLoaded){
                synchronized (SpawnerProcessor.lock_ActiveSpawners) {
                    sp.activeSpawners.remove(info.getBasicLocation());
                }
            }
        }
        else if (sp.activeSpawners.containsKey(info.getBasicLocation())) {
            if (main.debugInfo.doesSpawnerMeetDebugCriteria(main, DebugType.SPAWNER_DEACTIVATION, info))
                Utils.logger.info("no longer tracking spawner: " + Utils.showSpawnerLocation(cs));
            synchronized (SpawnerProcessor.lock_ActiveSpawners) {
                sp.activeSpawners.remove(info.getBasicLocation());
            }
        }
    }

    public void configReloaded(){
        this.recheckCriteria = true;
        sp.lastWGCheckTicks = -1;
    }
}
