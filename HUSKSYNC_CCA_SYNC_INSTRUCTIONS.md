# HuskSync — Ajout de la synchronisation Cardinal Components (Trinkets + Origins)

> Fichier d'instructions destiné à **Claude Code**, à exécuter à la racine du fork
> `flumz-tv/HuskSync`. Cible : module **Fabric, Minecraft 1.20.1**, mappings **Yarn**,
> build multi-version **Essential** (`gg.essential.multi-version`).

---

## 0. Contexte et objectif

HuskSync (build Fabric actuel) ne sérialise **que** des données vanilla :
`inventory, ender_chest, advancements, statistics, potion_effects, game_mode,
flight_status, attributes, health, hunger, experience, location`.

**Trinkets** et **Origins** ne stockent rien dans ces données : tous les deux passent
par **Cardinal Components API (CCA)**. Sur une entité, CCA sérialise ses composants dans
le NBT de l'entité sous la clé **`cardinal_components`**. HuskSync ne capture jamais cette
clé → l'origine, les powers et les trinkets ne traversent pas le changement de serveur.

**Objectif :** ajouter **un seul** type de donnée custom (`cardinal_components`) qui
capture et restaure ce tag NBT. Il couvre **Trinkets + Origins en une fois** (et tout
autre mod basé sur CCA : Pehkui, etc.).

> ⚠️ **Hors périmètre — Sophisticated Backpacks.** Le contenu d'un backpack n'est PAS
> dans le NBT de l'item : l'item ne porte qu'un `contentsUuid`, et le contenu vit dans le
> world-save du serveur, indexé par cet UUID. Aucune modif de HuskSync ne peut le
> synchroniser (les deux serveurs ont des storages disjoints). Ne pas tenter de le traiter
> ici. Solutions séparées : storage partagé entre serveurs, ou remplacer par un sac
> « NBT-based » (Travelers Backpack), ou bloquer le transfert.

---

## 1. Vérifications préalables (à faire avant de coder)

1. Confirmer que le dossier de version **`fabric/1.20.1/`** existe (avec son
   `gradle.properties` pointant sur `net.fabricmc:yarn:1.20.1+build.X:v2`). Le `master`
   public ne contient que 1.21.x ; le build 1.20.1 vit sur la branche/working-tree de
   l'utilisateur. **Si `fabric/1.20.1/` est absent, s'arrêter et le signaler.**
2. Repérer les 3 points d'intégration (déjà localisés) :
   - `fabric/src/main/java/net/william278/husksync/data/FabricData.java`
     → pattern Lombok/Gson des classes de données (voir `Experience`, `GameMode`).
   - `fabric/src/main/java/net/william278/husksync/data/FabricUserDataHolder.java`
     → méthode `getData(Identifier id)` = **capture** (lecture live depuis le joueur).
   - `fabric/src/main/java/net/william278/husksync/FabricHuskSync.java`
     → bloc `initialize("data serializers", ...)` = **enregistrement**.

Le mécanisme HuskSync (déjà vérifié dans le code) :
- `UserDataHolder.getData()` itère `getRegisteredDataTypes()` (= clés des serializers
  enregistrés), filtre `isEnabled`, et appelle `getData(id)` pour chaque type.
- À l'application, `applySnapshot()` trie par dépendances puis appelle `data.apply(...)`.
- Un identifiant **custom** (namespace ≠ `husksync`) est `enabled` par défaut.
- **Piège connu** : pour un id custom, `FabricUserDataHolder.getData(id)` lit par défaut
  le `customDataStore`, qui est **vide à la capture**. Il faut donc intercepter notre id
  **avant** le test `id.isCustom()` pour lire l'état live du joueur. (Géré en §3.)

---

## 2. Créer la classe de donnée `FabricData.CardinalComponents`

Dans **`FabricData.java`**, ajouter une nouvelle classe interne `static`, en copiant
exactement le style des autres (annotations Lombok + Gson + `Adaptable`). L'insérer par
exemple juste après la classe `Location` (ou n'importe où parmi les classes internes).

```java
@Getter
@Setter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public static class CardinalComponents extends FabricData implements Adaptable {

    // Clé NBT sous laquelle Cardinal Components stocke les composants d'entité.
    // Stable sur CCA 2.x→5.x. NE PAS changer sans vérifier (voir §6, étape de debug).
    private static final String CCA_NBT_KEY = "cardinal_components";

    // Identifiant custom (namespace != "husksync" => isCustom() == true => enabled).
    // Dépendances optionnelles: appliqué APRÈS attributes & health pour éviter que la
    // sync d'attributs/PV de HuskSync n'écrase les modificateurs posés par les powers
    // Origins (cf. le récent fix "health/maxhealth gone after server switch").
    public static final net.william278.husksync.data.Identifier IDENTIFIER =
            net.william278.husksync.data.Identifier.from(
                    "husksync_cca", "cardinal_components",
                    java.util.Set.of(
                            net.william278.husksync.data.Identifier.Dependency.optional("attributes"),
                            net.william278.husksync.data.Identifier.Dependency.optional("health")
                    )
            );

    // NBT des composants, sérialisé en SNBT (texte). Robuste et multi-version ;
    // pas de dépendance à NbtIo dont la signature change selon les versions MC.
    @SerializedName("components")
    private String components;

    @NotNull
    public static FabricData.CardinalComponents from(@NotNull String components) {
        return new FabricData.CardinalComponents(components);
    }

    /**
     * CAPTURE : lit l'état live du joueur.
     * On sérialise tout le NBT de l'entité puis on extrait le sous-tag CCA.
     * Aucune dépendance à l'API CCA n'est nécessaire ici (NBT vanilla pur).
     */
    @NotNull
    public static FabricData.CardinalComponents adapt(@NotNull ServerPlayerEntity player,
                                                      @NotNull FabricHuskSync plugin) {
        final net.minecraft.nbt.NbtCompound root = new net.minecraft.nbt.NbtCompound();
        player.writeNbt(root);
        final net.minecraft.nbt.NbtCompound cca = root.getCompound(CCA_NBT_KEY);
        if (cca.isEmpty()) {
            plugin.debug("[CCA] No '" + CCA_NBT_KEY + "' tag found for "
                    + player.getGameProfile().getName() + " (keys: " + root.getKeys() + ")");
        }
        return from(cca.toString()); // SNBT
    }

    /**
     * APPLICATION : restaure le sous-tag CCA puis re-synchronise vers le client.
     */
    @Override
    public void apply(@NotNull FabricUser user, @NotNull FabricHuskSync plugin) {
        final ServerPlayerEntity player = user.getPlayer();
        if (components == null || components.isBlank() || components.equals("{}")) {
            return;
        }
        try {
            final net.minecraft.nbt.NbtCompound cca =
                    net.minecraft.nbt.StringNbtReader.parse(components);
            final net.minecraft.nbt.NbtCompound wrapper = new net.minecraft.nbt.NbtCompound();
            wrapper.put(CCA_NBT_KEY, cca);
            // readNbt ne lit que les clés présentes (tout est gardé par contains()),
            // donc un wrapper ne contenant que cardinal_components ne touche qu'à CCA.
            player.readNbt(wrapper);

            // Re-sync vers le client (sinon l'écran trinkets / l'origine restent vides
            // jusqu'au prochain relog, car CCA sync le client AVANT cette restauration).
            FabricCardinalSync.resync(player);
        } catch (Throwable e) {
            plugin.log(java.util.logging.Level.WARNING,
                    "[CCA] Failed to apply cardinal_components to "
                            + player.getGameProfile().getName(), e);
        }
    }
}
```

> Remarque imports : `@SerializedName`, `Adaptable`, `ServerPlayerEntity`, `FabricUser`,
> `FabricHuskSync`, `Level`, les annotations Lombok et `NotNull` sont **déjà importés**
> en tête de `FabricData.java`. Les types `net.minecraft.nbt.*`, `Identifier` et `Set`
> sont écrits en **chemin complet** ci-dessus pour éviter d'avoir à toucher au bloc
> d'imports — tu peux les remonter en `import` si tu préfères, mais attention : la classe
> `Identifier` ici est `net.william278.husksync.data.Identifier`, **pas**
> `net.minecraft.util.Identifier` (déjà importé dans ce fichier). D'où le chemin complet.

---

## 3. Brancher la CAPTURE dans `FabricUserDataHolder.getData(...)`

Dans **`FabricUserDataHolder.java`**, méthode `default Optional<? extends Data> getData(Identifier id)`,
ajouter le court-circuit **tout en haut**, AVANT le bloc `if (id.isCustom()) {...}` :

```java
@Override
default Optional<? extends Data> getData(@NotNull Identifier id) {
    // --- AJOUT : capture live des composants Cardinal (Trinkets / Origins) ---
    if (id.equals(FabricData.CardinalComponents.IDENTIFIER)) {
        return Optional.of(FabricData.CardinalComponents.adapt(getPlayer(), (FabricHuskSync) getPlugin()));
    }
    // --- FIN AJOUT ---

    if (id.isCustom()) {
        return Optional.ofNullable(getCustomDataStore().get(id));
    }
    // ... switch existant inchangé ...
}
```

> `getPlugin()` est disponible via `UserDataHolder`. Vérifie le cast : dans ce module
> `getPlugin()` renvoie `HuskSync` ; caste en `FabricHuskSync`. Importer `FabricHuskSync`
> si nécessaire (`net.william278.husksync.FabricHuskSync`).

Aucune autre modif dans ce fichier : à l'application, le loop commun
(`UserDataHolder.applySnapshot`) gère le custom id automatiquement (il le range dans le
`customDataStore` puis appelle `apply()`).

---

## 4. Enregistrer le serializer dans `FabricHuskSync.java`

Dans **`FabricHuskSync.java`**, bloc `initialize("data serializers", (plugin) -> { ... })`,
ajouter la ligne juste **avant** `validateDependencies();` :

```java
// Sync Cardinal Components (Trinkets, Origins, ...) — seulement si CCA est présent
if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cardinal-components-entity")
        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("trinkets")
        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("origins")) {
    registerSerializer(
            FabricData.CardinalComponents.IDENTIFIER,
            new Serializer.Json<>(this, FabricData.CardinalComponents.class)
    );
}
```

`Serializer` et `FabricData` sont déjà importés/référencés dans ce fichier (cf. les autres
`registerSerializer(...)`). `Serializer.Json<>` est exactement ce qu'utilisent `statistics`,
`game_mode`, etc.

---

## 5. La re-synchronisation client (`FabricCardinalSync`)

C'est l'étape qui demande l'API CCA. Créer un petit helper **séparé** pour isoler les
imports CCA :

**Nouveau fichier :**
`fabric/src/main/java/net/william278/husksync/data/FabricCardinalSync.java`

```java
package net.william278.husksync.data;

import dev.onyxstudios.cca.api.v3.component.ComponentKey;
import dev.onyxstudios.cca.api.v3.component.ComponentProvider;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Force la re-synchronisation serveur -> client de tous les composants Cardinal
 * après restauration NBT (Trinkets, Origins, ...). Sans ça le client garde l'état
 * (vide) envoyé au join, jusqu'au prochain relog.
 *
 * Isolé dans sa propre classe pour que les classes CCA ne soient chargées QUE si
 * l'enregistrement a eu lieu (cf. garde isModLoaded dans FabricHuskSync).
 */
final class FabricCardinalSync {

    private FabricCardinalSync() {
    }

    static void resync(ServerPlayerEntity player) {
        final ComponentProvider provider = (ComponentProvider) player;
        // Re-sync de chaque composant attaché au joueur.
        for (ComponentKey<?> key : provider.getComponentContainer().keys()) {
            try {
                key.sync(player);
            } catch (Throwable ignored) {
                // composant non synchronisable : on ignore
            }
        }
    }
}
```

> ⚠️ **À VÉRIFIER contre `cardinal-components-api 5.2.2`** (la version présente dans le
> modpack, groupe maven `dev.onyxstudios.cardinal-components-api`, packages `dev.onyxstudios.cca.*`) :
> - `ComponentProvider` et `ComponentKey` → package `dev.onyxstudios.cca.api.v3.component`.
> - `provider.getComponentContainer()` renvoie un `ComponentContainer`.
> - `ComponentContainer#keys()` → `Set<ComponentKey<?>>`. **Si la méthode s'appelle
>   différemment** (ex. itérable directement, ou `.keys()` absent), adapte en regardant la
>   classe `ComponentContainer` dans le jar décompilé. `ComponentKey#sync(Object)` est, lui,
>   stable.
> - Si quoi que ce soit refuse de résoudre, **fallback acceptable** : supprimer l'appel
>   `FabricCardinalSync.resync(player);` dans `apply()`. Les données seront bien restaurées
>   côté serveur ; le client se rafraîchira au prochain relog. Tester d'abord SANS sync
>   (voir §6) avant de t'acharner sur l'API.

### Dépendance build (gradle), gated 1.20.1 uniquement

Dans **`fabric/build.gradle`**, ajouter le repo Ladysnake et la dépendance
**compileOnly** UNIQUEMENT pour 1.20.1 (sinon les builds 1.21.x casseront) :

```gradle
repositories {
    maven { url 'https://s01.oss.sonatype.org/content/repositories/snapshots/' }
    maven { url 'https://maven.nucleoid.xyz' }
    maven { url 'https://maven.ladysnake.org/releases' } // <-- AJOUT (Cardinal Components)
}

dependencies {
    // ... deps existantes ...

    // AJOUT : Cardinal Components API, seulement pour la cible 1.20.1
    if (project.name == "1.20.1") {
        modCompileOnly "dev.onyxstudios.cardinal-components-api:cardinal-components-base:5.2.2"
        modCompileOnly "dev.onyxstudios.cardinal-components-api:cardinal-components-entity:5.2.2"
    }
}
```

> `compileOnly` (pas `include`) : CCA est déjà fourni à l'exécution par le mod
> Origins/Trinkets sur le serveur. On ne l'embarque pas. Si `cardinal-components-base`
> ne résout pas seul, `cardinal-components-entity` le tire en transitif — garder les deux
> par sécurité. Si le repo Ladysnake ne sert pas la 5.2.2, essayer le maven Modrinth ou
> récupérer la version exacte affichée par le mod CCA du modpack.

---

## 6. Build & test

### Build (cible 1.20.1)

```bash
./gradlew :fabric:1.20.1:remapJar --no-daemon
# Le jar remappé sort dans target/
ls -lh target/*.jar
```

Si erreur de compilation sur les symboles CCA → voir l'encadré ⚠️ du §5 (vérifier
`keys()` / fallback sans sync).

### Test fonctionnel (2 serveurs Fabric 1.20.1 reliés à HuskSync)

Déposer le jar dans `mods/` des **deux** serveurs (SMP + Ville/Créatif), relancer.

Checklist :
1. Au démarrage, log attendu : `Registered custom data type: husksync_cca:cardinal_components`.
2. Choisir une **origine** + équiper des **trinkets** sur le serveur A.
3. Passer sur le serveur B :
   - [ ] L'écran de sélection d'origine ne réapparaît PAS (origine conservée).
   - [ ] Les trinkets équipés sont présents et visibles dans l'écran trinkets.
   - [ ] Les **powers/attributs** Origins sont actifs (vol, PV bonus, etc.).
4. Faire A→B→A pour vérifier la non-perte / non-duplication.

### Étape de debug si « rien ne se synchronise »

Si le tag est vide à la capture, le log `[CCA] No 'cardinal_components' tag found ... (keys: [...])`
listera les clés réelles du NBT joueur. **Si la clé CCA porte un autre nom** dans cette
version, remplacer la constante `CCA_NBT_KEY` par le nom affiché et rebuild.

---

## 7. Problèmes connus / points de vigilance

- **Powers Origins absents alors que l'origine est correcte.** Apoli ré-applique
  normalement les powers au tick suivant le chargement du composant. Si ce n'est pas le
  cas : la voie propre est d'ajouter un `modCompileOnly` sur **Apoli** (même repo gating
  1.20.1) et, dans `apply()` après `readNbt`, de forcer la ré-évaluation des powers
  (`PowerHolderComponent` côté Apoli). À n'ajouter QUE si le test 3 échoue — ne pas
  le faire à l'aveugle.
- **Conflit attributs/PV avec HuskSync.** Les dépendances optionnelles `attributes` +
  `health` posées sur `IDENTIFIER` font appliquer CCA APRÈS la sync PV/attributs de
  HuskSync, ce qui limite l'écrasement des modificateurs de powers. Si des PV max
  « fantômes » apparaissent, tester en désactivant `synchronization.features.attributes`
  (ou `health`) dans la config HuskSync sur les serveurs concernés.
- **Race au join avec l'écran Origins.** HuskSync restaure les données un court instant
  après le join ; un flash de l'écran de sélection est possible une seule fois. La
  restauration de l'origine empêche la réapparition aux connexions suivantes.
- **Ne pas toucher au module `bukkit`** ni au module `common` : tout est contenu dans
  `fabric/`. L'`Identifier` custom est créé via l'API publique existante (`Identifier.from`),
  donc aucune modif de `common/.../Identifier.java` n'est nécessaire.

---

## 8. Résumé des changements

| Fichier | Action |
|---|---|
| `fabric/.../data/FabricData.java` | + classe interne `CardinalComponents` (capture/apply) |
| `fabric/.../data/FabricUserDataHolder.java` | + court-circuit dans `getData(id)` (capture live) |
| `fabric/.../FabricHuskSync.java` | + `registerSerializer(...)` gated `isModLoaded` |
| `fabric/.../data/FabricCardinalSync.java` | **nouveau** helper de re-sync client (API CCA) |
| `fabric/build.gradle` | + repo Ladysnake & `modCompileOnly` CCA gated `1.20.1` |

Aucune modif côté `common`, `bukkit`, ou des autres versions Fabric.
