# MySmartHome

Application Android en **Kotlin** permettant de piloter un **ESP32** par **commande vocale locale**.  
Le projet repose sur trois modèles **TensorFlow Lite** distincts pour reconnaître successivement :

- la **pièce**
- l'**objet**
- l'**action**

Une fois la commande vocale interprétée, l'application envoie une requête HTTP à l'ESP32 pour exécuter l'action demandée.

---

## Objectif du projet

L'objectif de **MySmartHome** est de proposer une interface simple de contrôle domotique vocal sur réseau local, sans passer par un assistant cloud.

Exemple de scénario :

1. l'utilisateur prononce une **pièce**
2. l'application reconnaît ensuite un **objet**
3. l'application reconnaît enfin une **action**
4. la commande finale est envoyée à l'ESP32

Exemple :

`salon + led1 + on`

---

## Fonctionnalités principales

- reconnaissance vocale locale sur Android
- enregistrement audio via le microphone du téléphone
- extraction de caractéristiques audio avant inférence
- utilisation de **3 modèles TFLite quantifiés int8**
- interface Android en **Jetpack Compose**
- communication HTTP avec l'ESP32
- vérification automatique de la connectivité de l'ESP32
- sélection manuelle possible dans l'interface en complément de la voix

---

## Architecture générale

```text
Voix utilisateur
      ↓
Enregistrement audio (1 seconde)
      ↓
Extraction de features audio
      ↓
Inférence TFLite
   ├─ modèle pièces
   ├─ modèle objets
   └─ modèle actions
      ↓
Mapping des labels reconnus
      ↓
Requête HTTP envoyée à l'ESP32
      ↓
Pilotage des LEDs / objets connectés
```

---

## Technologies utilisées

### Côté Android

- **Kotlin**
- **Jetpack Compose**
- **TensorFlow Lite Interpreter**
- **Coroutines**
- **HTTP via `HttpURLConnection`**

### Traitement audio

- capture audio en **PCM 16 bits / 16 kHz**
- extraction de caractéristiques audio de type **STFT log**
- présence d'un extracteur **Log-Mel Spectrogram** dans le projet

### Côté embarqué

- **ESP32**
- serveur HTTP local
- pilotage des sorties GPIO

---

## Structure du code

```text
mysmarthome/
├── MainActivity.kt
├── data/
│   ├── audio/
│   │   └── LogMelSpectrogram.kt
│   └── model/
│       └── TFLiteKeywordDetector.kt
├── network/
│   └── Esp32HttpClient.kt
└── voice/
    ├── AudioRecorder.kt
    ├── LiveAudioClassifier.kt
    └── StftLogFeatureExtractor.kt
```

### Rôle des principaux fichiers

- **MainActivity.kt** : interface utilisateur, navigation entre les écrans, logique globale de reconnaissance et envoi des commandes.
- **TFLiteKeywordDetector.kt** : chargement des modèles TensorFlow Lite, quantification/déquantification et inférence.
- **AudioRecorder.kt** : enregistrement d'une seconde d'audio depuis le microphone.
- **StftLogFeatureExtractor.kt** : transformation du signal audio en tenseur exploitable par les modèles.
- **Esp32HttpClient.kt** : envoi des requêtes HTTP vers l'ESP32 et vérification de la connexion.

---

## Labels utilisés par les modèles

### Pièces

Labels présents dans l'application :

- `marvin` → salon
- `house` → cuisine
- `tree` → chambre

Labels listés dans le code des modèles :

- `bed`
- `house`
- `tree`
- `marvin`
- `sheila`

### Objets

- `one` → `led1`
- `two` → `led2`
- `three` → `led3`

Labels listés dans le code des modèles :

- `one`
- `two`
- `three`
- `four`
- `five`

### Actions

- `on` → allumer
- `off` → éteindre

Labels listés dans le code des modèles :

- `on`
- `off`
- `up`
- `down`
- `stop`

---

## Communication avec l'ESP32

L'application envoie une requête HTTP de la forme :

```http
GET /cmd?room=<piece>&object=<objet>&action=<action>
```

Exemple :

```http
GET /cmd?room=salon&object=led1&action=on
```

L'adresse IP par défaut utilisée dans l'application est :

```text
192.168.4.1
```

Ce choix correspond au cas classique où le téléphone est connecté au point d'accès Wi‑Fi de l'ESP32.

---

## Déroulement d'une commande vocale

1. l'utilisateur choisit ou prononce une **pièce**
2. l'application enregistre l'audio
3. le modèle **rooms** prédit un label
4. l'utilisateur choisit ou prononce un **objet**
5. le modèle **objects** prédit un label
6. l'utilisateur choisit ou prononce une **action**
7. le modèle **actions** prédit un label
8. les labels sont convertis vers les valeurs attendues par l'ESP32
9. une requête HTTP est envoyée

---

## Préparation du projet

### 1. Cloner le dépôt

```bash
git clone <URL_DU_DEPOT>
cd MySmartHome
```

### 2. Ouvrir le projet dans Android Studio

Ouvrir le projet Android complet dans **Android Studio**.

### 3. Ajouter les modèles TFLite dans les assets

Les modèles utilisés par l'application doivent être placés dans :

```text
app/src/main/assets/
```

Noms attendus dans le code :

- `rooms_model_pruned_int8.tflite`
- `objects_model_pruned_int8.tflite`
- `actions_model_pruned_int8.tflite`

### 4. Vérifier les permissions Android

L'application nécessite au minimum :

- `RECORD_AUDIO`
- `INTERNET`

### 5. Connecter le téléphone au réseau de l'ESP32

Le smartphone doit être connecté au même réseau local que l'ESP32.

---

## Lancer l'application

1. démarrer l'ESP32
2. connecter le téléphone au Wi‑Fi de l'ESP32
3. lancer l'application Android
4. vérifier ou modifier l'adresse IP de l'ESP32
5. autoriser l'accès au microphone
6. effectuer la reconnaissance vocale étape par étape

---

## Seuil de décision

Le seuil minimal de confiance utilisé pour accepter une prédiction est actuellement :

```kotlin
0.6f
```

Une prédiction sous ce seuil est rejetée.

---

## État actuel du projet

Le code fourni montre une version fonctionnelle orientée démonstration / prototype avec les caractéristiques suivantes :

- reconnaissance en **3 étapes successives**
- enregistrement sur une fenêtre fixe de **1 seconde**
- support effectif actuel de **3 pièces**, **3 objets** et **2 actions** dans l'application
- certains labels présents dans les listes des modèles ne sont **pas encore exploités** dans le mapping final

---

## Limites actuelles

- vocabulaire actuellement limité
- pipeline vocal séquentiel en trois étapes
- dépendance à des noms de modèles précis dans les assets
- absence de gestion d'historique des commandes
- absence de retour d'état détaillé depuis l'ESP32 côté interface
- l'extrait de code fourni ne contient pas tout le projet Android complet (Gradle, manifest, ressources, modèles, code ESP32, etc.)

---

## Pistes d'amélioration

- fusionner la reconnaissance en une commande vocale complète
- étendre le nombre de pièces, d'objets et d'actions supportés
- améliorer l'interface utilisateur et le retour d'état
- ajouter la gestion d'autres équipements domotiques
- intégrer une authentification ou une sécurisation des requêtes
- afficher des logs ou métriques de confiance plus détaillés
- connecter l'application à une base locale pour mémoriser les préférences utilisateur

---

## Auteur

Andrianina ANDRIANTSIALONINA
Massil AIT CHALLAL

---

## Licence

- Master 2 SIME, université de Rouen

