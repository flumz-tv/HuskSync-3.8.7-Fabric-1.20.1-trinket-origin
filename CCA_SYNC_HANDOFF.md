# Reprise — Synchronisation Cardinal Components (Origins + Trinkets) — HuskSync Fabric 1.20.1

> **Export de session Claude Code.** Ce dossier (`D:\download\HuskSync-3.8.7\HuskSync-3.8.7\`)
> est l'arbre de travail **correct** : il est porté en 1.20.1 et compile. L'autre dossier
> `D:\GITHUB\HuskSync` est la source 1.21.8 d'origine, **incomplète et qui ne build pas** en
> 1.20.1 — à ignorer.
>
> Pour continuer : ouvre une nouvelle session Claude Code **dans ce dossier** et pointe-la
> sur ce fichier.

---

## 1. Objectif

HuskSync ne sérialise que des données vanilla (inventaire, ender chest, advancements, stats,
effets, gamemode, vol, attributs, vie, faim, xp, position). **Origins** et **Trinkets**
passent par **Cardinal Components API (CCA)**, qui stocke ses composants dans le NBT de
l'entité sous la clé `cardinal_components`. HuskSync ne capturait jamais cette clé → origine,
powers et trinkets ne traversaient pas le changement de serveur.

**Solution livrée :** un type de donnée custom `husksync_cca:cardinal_components` qui
capture/restaure ce sous-tag NBT. Couvre **Origins + Trinkets** (et tout mod CCA) en une fois.

> ⚠️ **Sophisticated Backpacks est HORS DE PORTÉE et ne peut PAS être synchronisé** : le
> contenu d'un backpack vit dans le world-save du serveur (indexé par `contentsUuid`), pas
> dans le NBT de l'item. Deux serveurs = deux storages disjoints → aucune modif HuskSync ne
> peut le transférer.

---

## 2. Décisions actées

- **Seul le build 1.20.1 compte** (les autres versions Fabric n'ont pas besoin de compiler).
- **Resync client immédiate par réflexion** : aucune dépendance CCA compile-time, aucun
  changement de `fabric/build.gradle`. Le code compile sur toutes versions ; la resync ne
  s'exécute qu'au runtime là où CCA est présent.

---

## 3. Fichiers modifiés (3) — tous dans cet arbre

### a) `fabric/src/main/java/net/william278/husksync/data/FabricData.java`
Classe interne `static CardinalComponents extends FabricData implements Adaptable`, insérée
après `Location` (vers la ligne 532). Points clés :
- `CCA_NBT_KEY = "cardinal_components"`.
- `IDENTIFIER = Identifier.from("husksync_cca", "cardinal_components", Set.of(...))` avec
  dépendances **optionnelles** sur `attributes` et `health` (appliqué APRÈS eux pour ne pas
  écraser les modificateurs des powers Origins — cf. fix « health/maxhealth gone after server
  switch », commit c3a6afd). Utilise `Dependency.optional(Key.key(...))` car la surcharge
  `optional(String)` est `private` dans `common/.../Identifier.java`.
- Champ `@SerializedName("components") String components` (SNBT texte, robuste multi-version).
- `adapt(...)` : capture via `player.writeNbt(root)` puis `root.getCompound(CCA_NBT_KEY)`.
- `apply(...)` : `StringNbtReader.parse`, envelopper sous `CCA_NBT_KEY`, `player.readNbt`,
  puis `resync(player)`.
- `resync(...)` : réflexion `getComponentContainer()` → `keys()` → `key.sync(player)`, le
  tout en `try/catch(Throwable)` silencieux.

> **IMPORTANT — préprocesseur :** les appels NBT 1.20.1 sont gardés par
> `//#if MC==12001` avec le code réel en lignes `//$$` (et un `//#else` no-op). C'est
> obligatoire : en 1.21.x `getCompound()` renvoie `Optional<NbtCompound>` et le code brut ne
> compilerait pas. **Ne pas dégrader ces gardes.**

### b) `fabric/src/main/java/net/william278/husksync/data/FabricUserDataHolder.java`
Court-circuit en tout début de `getData(Identifier id)`, **AVANT** `if (id.isCustom())`
(sinon la capture lirait le `customDataStore` vide) :
```java
if (id.equals(FabricData.CardinalComponents.IDENTIFIER)) {
    return Optional.of(FabricData.CardinalComponents.adapt(
            getPlayer(), (net.william278.husksync.FabricHuskSync) getPlugin()));
}
```

### c) `fabric/src/main/java/net/william278/husksync/FabricHuskSync.java`
Juste avant `validateDependencies();` (après l'enregistrement LOCATION), enregistre le
serializer seulement si un mod CCA est chargé :
```java
if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cardinal-components-entity")
        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("trinkets")
        || net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("origins")) {
    registerSerializer(
            FabricData.CardinalComponents.IDENTIFIER,
            new Serializer.Json<>(this, FabricData.CardinalComponents.class));
}
```

**Aucune modif** de `common`, `bukkit`, `build.gradle`, ni des autres versions Fabric.

---

## 4. Build (depuis ce dossier)

Pré-requis env (MC 1.20.1 → toolchain Java 17, mais le wrapper exige un JDK récent) :
```powershell
$env:JAVA_HOME = "C:\Program Files\JAVA\jdk-21.0.11"
.\gradlew :fabric:1.20.1:remapJar --no-daemon
```
> Note : `build.gradle` force déjà `compileJava.options.release = 17` pour le projet `1.20.1`
> (et 21 pour les autres). La graphe de préprocesseur (`fabric/root.gradle`) contient bien le
> nœud `1.20.1` (`fabric12001.link(fabric12101, null)`).

Le jar remappé sort dans `target/`.

---

## 5. Jar livrable

```
D:\download\HuskSync-3.8.7\HuskSync-3.8.7\target\HuskSync-Fabric-3.8.7-unknown+mc.1.20.1.jar
```
Vérifié : contient `FabricData$CardinalComponents.class`.

Ancien jar (SANS CCA) actuellement dans l'instance CurseForge :
```
C:\Users\Flumz\curseforge\minecraft\Instances\DEV MINECRAFT\mods\HuskSync-Fabric-3.8.7+mc.1.20.1.jar
```

---

## 6. Déploiement

1. Copier le nouveau jar dans le dossier `mods/` des **DEUX** serveurs Fabric 1.20.1
   (la sync ne marche que si les deux ont la même version).
2. Relancer les serveurs.
3. Au démarrage, vérifier le log :
   `Registered custom data type: husksync_cca:cardinal_components`.

---

## 7. Test fonctionnel

1. Serveur A : choisir une **origine** + équiper des **trinkets**.
2. Passer sur serveur B :
   - [ ] L'écran de sélection d'origine ne réapparaît PAS.
   - [ ] Les trinkets équipés sont visibles immédiatement (resync réflexion).
   - [ ] Powers/attributs Origins actifs (vol, PV bonus…).
3. A→B→A : pas de perte ni duplication.

---

## 8. Debug si « rien ne se synchronise »

- Si le tag est vide à la capture, le log
  `[CCA] No 'cardinal_components' tag found ... (keys: [...])` liste les clés réelles du NBT
  joueur. Si la clé CCA porte un autre nom dans cette version, changer `CCA_NBT_KEY` et
  rebuild.
- Si trinkets/origine n'apparaissent qu'au relog, la resync réflexion a échoué silencieusement
  (données quand même restaurées côté serveur) — vérifier les noms de méthodes CCA
  (`getComponentContainer` / `keys` / `sync`) sur la version exacte du modpack.
