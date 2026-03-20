# Go2ScenarioApp

Android client for interacting with a box-based scenario server, including state visualization, sensing/disposal actions, and nearby box detection through Bluetooth beacons.

## Overview

`Go2ScenarioApp` is an Android application built with **Kotlin** and **Jetpack Compose**.  
It connects to a backend service that manages box states and task execution, and provides a mobile interface for:

- viewing the current state of boxes
- requesting sensing actions for property **X** or **Y**
- requesting disposal actions for property **X** or **Y**
- cancelling active sensing/disposal requests
- tracking nearby boxes through Bluetooth beacon discovery

The app is structured as a lightweight client for scenario-based human–robot or operator–server interaction workflows.

## Features

- **Jetpack Compose UI**
- **Retrofit + Moshi** networking
- **Bluetooth / BLE beacon scanning**
- Box state retrieval from backend
- Action requests for:
  - sensing `X`
  - sensing `Y`
  - disposing `X`
  - disposing `Y`
- Action cancellation support
- Server time retrieval
- Agent parameter retrieval

## Project Structure

```text
Go2ScenarioApp/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/boxclient/
│   │   │   ├── MainActivity.kt
│   │   │   ├── BeaconScanner.kt
│   │   │   ├── data/
│   │   │   │   ├── ApiModels.kt
│   │   │   │   ├── BoxApi.kt
│   │   │   │   └── NetworkModule.kt
│   │   │   └── ui/
│   │   │       └── BoxViewModel.kt
│   │   ├── res/
│   │   └── AndroidManifest.xml
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew
