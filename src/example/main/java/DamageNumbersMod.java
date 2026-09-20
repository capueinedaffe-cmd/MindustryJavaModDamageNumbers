package damage_numbers;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import arc.util.serialization.Jval;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Unit;
import mindustry.gen.WorldLabel;
import mindustry.mod.Mod;

import java.util.concurrent.ConcurrentHashMap;

public class DamageNumbersMod extends Mod {

    public DamageNumbersMod() {
        Log.info("===== CONSTRUCTOR EJECUTADO =====");
    }

    private static final String MOD_NAME = "damage-numbers";

    // --- Configuracion ---
    private boolean showAllyUnitDamage = true;
    private boolean showEnemyUnitDamage = true;
    private boolean showAllyBuildingDamage = true;
    private boolean showEnemyBuildingDamage = true;
    private float labelLifetimeTicks = 0.8f;
    private float fontSize = 1f;
    private float iconSize = 8f;      // tamano del icono dibujado, en unidades de mundo
    private float iconOffsetX = 4f;   // que tanto se corre el icono a la izquierda del numero
    private float holdSeconds = 0.15f; // tiempo quieto antes de empezar a flotar
    private float riseSpeed = 14f;      // velocidad de ascenso, en unidades de mundo por segundo
    private String damageMode = "raw";

    // --- Memoria de vida ---
    private final ConcurrentHashMap<Integer, Float> unitHealthMemory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Float> buildHealthMemory = new ConcurrentHashMap<>();

    // --- Cache de sprites personalizados (se cargan la primera vez que se piden) ---
    private final ObjectMapCache spriteCache = new ObjectMapCache();

    // --- Iconos activos en pantalla, dibujados aparte del texto ---
    private static class IconOverlay {
        TextureRegion region;
        float x, y, remaining, age;
    }
    private final Seq<IconOverlay> iconOverlays = new Seq<>();

    // --- Etiquetas de texto activas, para poder animarlas cuadro a cuadro ---
    private static class ActiveLabel {
        WorldLabel label;
        float age;
    }
    private final Seq<ActiveLabel> activeLabels = new Seq<>();

    @Override
    public void init() {
        Log.info("==== INIT EJECUTANDO ====");

        loadConfig();

        Log.info("[Damage Numbers] Registrando eventos...");

        Events.on(EventType.UnitDestroyEvent.class, e -> {
            if (e.unit != null) unitHealthMemory.remove(e.unit.id);
        });
        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if (e.tile != null && e.tile.build != null) {
                buildHealthMemory.remove(e.tile.build.id);
            }
        });

        // --- Anima los iconos y etiquetas activos cada frame ---
        // Usamos drawOver (no draw) para que los iconos queden SIEMPRE por
        // encima de destellos y efectos de particulas de los propios ataques.
        Events.run(EventType.Trigger.drawOver, () -> {
            float dt = Time.delta / 60f;

            for (int i = iconOverlays.size - 1; i >= 0; i--) {
                IconOverlay o = iconOverlays.get(i);

                o.age += dt;
                if (o.age > holdSeconds) o.y += riseSpeed * dt;

                // Reset ANTES de dibujar: limpia cualquier "mixcol" (tinte de
                // destello) que haya quedado activo de un efecto anterior en
                // este mismo frame, para que el icono muestre sus colores reales.
                Draw.reset();
                Draw.color(Color.white);
                Draw.rect(o.region, o.x, o.y, iconSize, iconSize);
                Draw.reset();

                o.remaining -= dt;
                if (o.remaining <= 0) iconOverlays.remove(i);
            }

            for (int i = activeLabels.size - 1; i >= 0; i--) {
                ActiveLabel a = activeLabels.get(i);

                a.age += dt;
                if (a.age > holdSeconds) a.label.y += riseSpeed * dt;

                if (a.age >= labelLifetimeTicks) activeLabels.remove(i);
            }
        });

        // --- Dano a unidades ---
        Events.on(EventType.UnitDamageEvent.class, e -> {
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
            float x = unit.x, y = unit.y + unit.hitSize / 2f;
            showDamageLabel(text, isAlly, x, y);
            spawnIcon(iconFor(e.bullet.type), x, y);
        });

        // --- Dano a edificios ---
        Events.on(EventType.BuildDamageEvent.class, e -> {
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
            showDamageLabel(text, isAlly, build.x, build.y);
            spawnIcon(iconFor(e.source.type), build.x, build.y);
        });

        Log.info("[Damage Numbers] Mod cargado correctamente!");
    }

    // --- Clasifica el tipo de dano y devuelve el icono a dibujar (o null si no hay) ---
    // Usa campos generales de BulletType, funciona sin importar la subclase especifica.
    private TextureRegion iconFor(BulletType type) {
        if (type == null) return null;

        boolean isElectric = type.status == StatusEffects.shocked
                || type.status == StatusEffects.electrified
                || type.lightningDamage >= 0;
        if (isElectric) return StatusEffects.shocked.uiIcon;

        if (type.status == StatusEffects.freezing) return StatusEffects.freezing.uiIcon;

        boolean isFire = type.status == StatusEffects.burning || type.makeFire;
        if (isFire) return StatusEffects.burning.uiIcon;

        if (type.status == StatusEffects.blasted) return StatusEffects.blasted.uiIcon;
        if (type.status == StatusEffects.corroded) return StatusEffects.corroded.uiIcon;
        if (type.status == StatusEffects.melting) return StatusEffects.melting.uiIcon;
        if (type.status == StatusEffects.wet) return StatusEffects.wet.uiIcon;

        // Splash/explosivo: icono PERSONALIZADO tuyo.
        // Coloca tu sprite en assets/sprites/icon-splash.png y se cargara solo.
        if (type.splashDamage > 0) return getCustomSprite("icon-splash");

        // Cinetico/directo (sin status especial): icono PERSONALIZADO tuyo.
        // Coloca tu sprite en assets/sprites/icon-kinetic.png y se cargara solo.
        return getCustomSprite("icon-kinetic");
    }

    // --- Carga (y cachea) un sprite personalizado desde assets/sprites/ ---
    // El nombre final en el atlas es "damage-numbers-<nombre>", ya que el
    // juego le agrega el nombre del mod como prefijo automaticamente.
    private TextureRegion getCustomSprite(String name) {
        String fullName = MOD_NAME + "-" + name;
        return spriteCache.get(fullName, () -> {
            if (!Core.atlas.has(fullName)) {
                Log.warn("[Damage Numbers] No se encontro el sprite '" + fullName
                        + "'. Coloca el archivo en assets/sprites/" + name + ".png");
                return null;
            }
            return Core.atlas.find(fullName);
        });
    }

    // --- Agrega un icono a la lista de dibujado, si existe ---
    private void spawnIcon(TextureRegion region, float worldX, float worldY) {
        if (region == null) return;

        IconOverlay overlay = new IconOverlay();
        overlay.region = region;
        overlay.x = worldX - iconOffsetX;
        overlay.y = worldY;
        overlay.remaining = labelLifetimeTicks;
        iconOverlays.add(overlay);
    }

    // --- Muestra el numero de dano, coloreado segun equipo (blanco aliado, rojo enemigo) ---
    private void showDamageLabel(String text, boolean isAlly, float worldX, float worldY) {
        // flagOutline = 1 << 1 = 2 (valor tomado del codigo fuente de WorldLabelComp,
        // escrito como literal para no depender de esa clase en tiempo de ejecucion)
        final byte FLAG_OUTLINE = 2;

        String colored = (isAlly ? "[white]" : "[scarlet]") + text + "[]";

        WorldLabel label = WorldLabel.create();
        label.text = colored;
        label.x = worldX;
        label.y = worldY;
        label.fontSize = fontSize;
        label.flags = FLAG_OUTLINE;
        label.duration = labelLifetimeTicks; // en segundos
        label.add();

        ActiveLabel active = new ActiveLabel();
        active.label = label;
        active.age = 0f;
        activeLabels.add(active);
    }

    // --- Configuracion ---
    private void loadConfig() {
        try {
            var mod = Vars.mods.getMod(MOD_NAME);
            if (mod == null) {
                Log.warn("[Damage Numbers] Mod no encontrado, usando valores por defecto");
                return;
            }

            var configFile = mod.root.child("config.hjson");
            if (!configFile.exists()) {
                Log.warn("[Damage Numbers] config.hjson no encontrado, usando valores por defecto");
                return;
            }

            Jval json = Jval.read(configFile.readString());

            showAllyUnitDamage = json.getBool("showAllyUnitDamage", showAllyUnitDamage);
            showEnemyUnitDamage = json.getBool("showEnemyUnitDamage", showEnemyUnitDamage);
            showAllyBuildingDamage = json.getBool("showAllyBuildingDamage", showAllyBuildingDamage);
            showEnemyBuildingDamage = json.getBool("showEnemyBuildingDamage", showEnemyBuildingDamage);
            labelLifetimeTicks = json.getFloat("labelLifetimeTicks", labelLifetimeTicks);
            fontSize = json.getFloat("fontSize", fontSize);
            iconSize = json.getFloat("iconSize", iconSize);
            iconOffsetX = json.getFloat("iconOffsetX", iconOffsetX);
            holdSeconds = json.getFloat("holdSeconds", holdSeconds);
            riseSpeed = json.getFloat("riseSpeed", riseSpeed);

            String mode = json.getString("damageMode", damageMode);
            if (mode.equals("raw") || mode.equals("true") || mode.equals("both")) {
                damageMode = mode;
            } else {
                Log.warn("[Damage Numbers] damageMode invalido: " + mode + ", usando 'raw'");
            }

            Log.info("[Damage Numbers] Configuracion cargada correctamente");
        } catch (Exception e) {
            Log.err("[Damage Numbers] Error al cargar config.hjson: " + e.getMessage());
            Log.err("[Damage Numbers] Usando valores por defecto");
        }
    }

    // --- Formateo del texto ---
    private String formatDamageText(float raw, float trueDamage) {
        String rawStr = String.valueOf(Math.round(raw));
        String trueStr = String.valueOf(Math.round(trueDamage));
        switch (damageMode) {
            case "raw": return rawStr;
            case "true": return trueStr;
            default: return rawStr + "(" + trueStr + ")";
        }
    }

    // --- Pequeno cache de sprites, para no buscar en el atlas en cada golpe ---
    private static class ObjectMapCache {
        private final ConcurrentHashMap<String, TextureRegion> cache = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> resolved = new ConcurrentHashMap<>();

        TextureRegion get(String key, java.util.function.Supplier<TextureRegion> loader) {
            if (resolved.containsKey(key)) return cache.get(key);
            TextureRegion region = loader.get();
            if (region != null) cache.put(key, region);
            resolved.put(key, true);
            return region;
        }
    }
}
