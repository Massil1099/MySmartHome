#include <WiFi.h>
#include <WebServer.h>

const char* ssid = "ESP32_SmartHome";
const char* password = "12345678";

WebServer server(80);

// ---------- SALON (marvin) ----------
const int SALON_LED1 = 21; // D21
const int SALON_LED2 = 19; // D19
const int SALON_LED3 = 18; // D18

// ---------- CUISINE (house) ----------
const int CUISINE_LED1 = 5;   // D5
const int CUISINE_LED2 = 17;  // D17
const int CUISINE_LED3 = 16;  // D16

// ---------- CHAMBRE (bed) ----------
const int CHAMBRE_LED1 = 4;   // D4
const int CHAMBRE_LED2 = 2;   // D2
const int CHAMBRE_LED3 = 15;  // D15

bool isValidRoom(String room) {
    room.toLowerCase();
    return room == "salon" || room == "cuisine" || room == "chambre";
}

String normalizeObject(String objectName) {
    objectName.toLowerCase();

    // On accepte à la fois les labels Android et les alias éventuels
    if (objectName == "one" || objectName == "led1") return "led1";
    if (objectName == "two" || objectName == "led2") return "led2";
    if (objectName == "three" || objectName == "led3") return "led3";

    return "";
}

bool isValidAction(String action) {
    action.toLowerCase();
    return action == "on" || action == "off";
}

int getPinForRoomAndObject(String room, String objectName) {
    room.toLowerCase();
    objectName = normalizeObject(objectName);

    if (room == "salon") {
        if (objectName == "led1") return SALON_LED1;
        if (objectName == "led2") return SALON_LED2;
        if (objectName == "led3") return SALON_LED3;
    }

    if (room == "cuisine") {
        if (objectName == "led1") return CUISINE_LED1;
        if (objectName == "led2") return CUISINE_LED2;
        if (objectName == "led3") return CUISINE_LED3;
    }

    if (room == "chambre") {
        if (objectName == "led1") return CHAMBRE_LED1;
        if (objectName == "led2") return CHAMBRE_LED2;
        if (objectName == "led3") return CHAMBRE_LED3;
    }

    return -1;
}

void handleCmd() {
    if (!server.hasArg("room") || !server.hasArg("object") || !server.hasArg("action")) {
        server.send(400, "text/plain", "Missing params: room, object, action required");
        return;
    }

    String room = server.arg("room");
    String objectName = server.arg("object");
    String action = server.arg("action");

    room.toLowerCase();
    objectName.toLowerCase();
    action.toLowerCase();

    Serial.printf("[HTTP] room=%s object=%s action=%s\n",
                  room.c_str(), objectName.c_str(), action.c_str());

    if (!isValidRoom(room)) {
        server.send(400, "text/plain", "Unknown room");
        return;
    }

    String normalizedObject = normalizeObject(objectName);
    if (normalizedObject == "") {
        server.send(400, "text/plain", "Unknown object");
        return;
    }

    if (!isValidAction(action)) {
        server.send(400, "text/plain", "Unknown action: use on/off");
        return;
    }

    int pin = getPinForRoomAndObject(room, normalizedObject);
    if (pin == -1) {
        server.send(400, "text/plain", "No pin mapping found");
        return;
    }

    if (action == "on") {
        digitalWrite(pin, HIGH);
        server.send(200, "text/plain", room + " -> " + normalizedObject + " ON");
    } else {
        digitalWrite(pin, LOW);
        server.send(200, "text/plain", room + " -> " + normalizedObject + " OFF");
    }
}

void handleRoot() {
    server.send(
            200,
            "text/plain",
            "ESP32 Smart Home OK\n"
            "Use: /cmd?room=salon&object=led1&action=on\n"
            "Rooms: salon, cuisine, chambre\n"
            "Objects: led1, led2, led3 (or one, two, three)\n"
            "Actions: on, off"
    );
}

void setup() {
    Serial.begin(115200);

    // Configuration des 9 LEDs en sortie
    pinMode(SALON_LED1, OUTPUT);
    pinMode(SALON_LED2, OUTPUT);
    pinMode(SALON_LED3, OUTPUT);

    pinMode(CUISINE_LED1, OUTPUT);
    pinMode(CUISINE_LED2, OUTPUT);
    pinMode(CUISINE_LED3, OUTPUT);

    pinMode(CHAMBRE_LED1, OUTPUT);
    pinMode(CHAMBRE_LED2, OUTPUT);
    pinMode(CHAMBRE_LED3, OUTPUT);

    // Tout éteindre au démarrage
    digitalWrite(SALON_LED1, LOW);
    digitalWrite(SALON_LED2, LOW);
    digitalWrite(SALON_LED3, LOW);

    digitalWrite(CUISINE_LED1, LOW);
    digitalWrite(CUISINE_LED2, LOW);
    digitalWrite(CUISINE_LED3, LOW);

    digitalWrite(CHAMBRE_LED1, LOW);
    digitalWrite(CHAMBRE_LED2, LOW);
    digitalWrite(CHAMBRE_LED3, LOW);

    WiFi.mode(WIFI_AP);
    bool ok = WiFi.softAP(ssid, password);

    Serial.println();
    Serial.println(ok ? "[WiFi] SoftAP started" : "[WiFi] SoftAP failed");
    Serial.print("[WiFi] SSID: ");
    Serial.println(ssid);
    Serial.print("[WiFi] AP IP: ");
    Serial.println(WiFi.softAPIP());

    server.on("/", handleRoot);
    server.on("/cmd", handleCmd);
    server.begin();

    Serial.println("[HTTP] server started");
}

void loop() {
    server.handleClient();
}