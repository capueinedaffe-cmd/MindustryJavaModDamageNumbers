// src/damage_numbers/DamageNumbersMod.java
package damage_numbers;

import arc.Events;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.game.EventType.*;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.mod.Mod;

import java.util.concurrent.ConcurrentHashMap;

public class DamageNumbersMod extends Mod {
    private static final String MOD_NAME = "damage-numbers";

    // --- Configuracion (valores por defecto, sobreescritos por config.json) ---
    private boolean showAllyUnitDamage = true;
    private boolean showEnemyUnitDamage = true;
    private boolean showAllyBuildingDamage = true;
    private boolean showEnemyBuildingDamage = true;
    private float labelLifetimeTicks = 40f;
    private String damageMode = "raw"; // "raw", "true", "both"

    // --- Memoria de vida (thread-safe), para calcular el dano "true" ---
    private final ConcurrentHashMap<Integer, Float> unitHealthMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Float> buildHealthMemory = new ConcurrentHashMap<>();

    // --- Contador de ids para las etiquetas flotantes ---
    private int nextLabelId = 1;

    @Override
    public void init() {
        loadConfig();

        // Limpiar memoria al destruir, para que no crezca sin limite
        Events.on(UnitDestroyEvent.class, e -> {
            if (e.unit != null) unitHealthMemory.remove(e.unit.id);
        });

        Events.on(BlockDestroyEvent.class, e -> {
            if (e.tile != null && e.tile.build != null) {
                buildHealthMemory.remove(e.tile.build.id);
            }
        });

        // --- Dano a unidades ---
        Events.on(UnitDamageEvent.class, e -> {
            if (e.bullet == null || e.unit == null) return;
            float rawDamage = e.bullet.damage;
            if (rawDamage <= 0) return;

            Unit unit = e.unit;
            int id = unit.id;

            Float prevHealthObj = unitHealthMemory.get(id);
            float prevHealth = (prevHealthObj != null) ? prevHealthObj : unit.health + rawDamage;
            float trueDamage = Math.max(prevHealth - unit.health, 0);
            unitHealthMemory.put(id, unit.health);

            Team playerTeam = Vars.player != null ? Vars.player.team() : null;
            boolean isAlly = playerTeam != null && playerTeam == unit.team;

            if (isAlly && !showAllyUnitDamage) return;
            if (!isAlly && !showEnemyUnitDamage) return;

            String text = formatDamageText(rawDamage, trueDamage);
            spawnLabel(text, unit.x, unit.y + unit.hitSize / 2);
        });

        // --- Dano a estructuras (incluye torretas) ---
        Events.on(BuildDamageEvent.class, e -> {
            if (e.source == null || e.build == null) return;
            float rawDamage = e.source.damage;
            if (rawDamage <= 0) return;

            Building build = e.build;
            int id = build.id;

            Float prevHealthObj = buildHealthMemory.get(id);
            float prevHealth = (prevHealthObj != null) ? prevHealthObj : build.health + rawDamage;
            float trueDamage = Math.max(prevHealth - build.health, 0);
            buildHealthMemory.put(id, build.health);

            Team playerTeam = Vars.player != null ? Vars.player.team() : null;
            boolean isAlly = playerTeam != null && playerTeam == build.team;

            if (isAlly && !showAllyBuildingDamage) return;
            if (!isAlly && !showEnemyBuildingDamage) return;

            String text = formatDamageText(rawDamage, trueDamage);
            spawnLabel(text, build.x, build.y);
        });

        Log.info("[Damage Numbers] Mod cargado correctamente en Java!");
    }

    // --- Metodos auxiliares ---

    private void spawnLabel(String text, float x, float y) {
        int id = nextLabelId++;
        if (nextLabelId > 1_000_000) nextLabelId = 1; // evita crecer sin limite en partidas largas

        Vars.ui.showLabel(text, id, labelLifetimeTicks, x, y);

        // Forzamos el borrado nosotros mismos, sin depender de una unidad de
        // tiempo interna que no podemos confirmar con certeza.
        Time.run(labelLifetimeTicks, () -> Vars.ui.showLabel(null, id, 0f, x, y));
    }

    private void loadConfig() {
        try {
            var mod = Vars.mods.getMod(MOD_NAME);
            if (mod == null) {
                Log.warn("[Damage Numbers] Mod no encontrado, usando valores por defecto");
                return;
            }

            var configFile = mod.root.child("config.json");
            if (!configFile.exists()) {
                Log.warn("[Damage Numbers] config.json no encontrado, usando valores por defecto");
                return;
            }

            Jval json = Jval.read(configFile.readString());

            showAllyUnitDamage = json.getBool("showAllyUnitDamage", showAllyUnitDamage);
            showEnemyUnitDamage = json.getBool("showEnemyUnitDamage", showEnemyUnitDamage);
            showAllyBuildingDamage = json.getBool("showAllyBuildingDamage", showAllyBuildingDamage);
            showEnemyBuildingDamage = json.getBool("showEnemyBuildingDamage", showEnemyBuildingDamage);
            labelLifetimeTicks = json.getFloat("labelLifetimeTicks", labelLifetimeTicks);

            String mode = json.getString("damageMode", damageMode);
            if (mode.equals("raw") || mode.equals("true") || mode.equals("both")) {
                damageMode = mode;
            } else {
                Log.warn("[Damage Numbers] damageMode invalido: " + mode + ", usando 'raw'");
            }

            Log.info("[Damage Numbers] Configuracion cargada correctamente");
        } catch (Exception e) {
            Log.err("[Damage Numbers] Error al cargar config.json: " + e.getMessage());
            Log.err("[Damage Numbers] Usando valores por defecto");
        }
    }

    private String formatDamageText(float raw, float trueDamage) {
        String rawStr = String.valueOf(Math.round(raw));
        String trueStr = String.valueOf(Math.round(trueDamage));
        switch (damageMode) {
            case "raw": return rawStr;
            case "true": return trueStr;
            default: return rawStr + "(" + trueStr + ")";
        }
    }
}
