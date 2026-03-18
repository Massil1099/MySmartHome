#include <WiFi.h>
#include <WebServer.h>

const char* ssid = "ESP32_SmartHome";
const char* password = "12345678";

WebServer server(80);

// LEDs par pièce
const int LED_SALON = 2;
const int LED_CUISINE = 4;
const int LED_CHAMBRE = 5;

bool isValidObject(String objectName) {
    objectName.toLowerCase();

    // On accepte plusieurs alias pour simplifier les tests
    return objectName == "led" ||
           objectName == "light" ||
           objectName == "one" ||
           objectName == "two" ||
           objectName == "three";
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

    if (!isValidObject(objectName)) {
        server.send(400, "text/plain", "Unknown object");
        return;
    }

    int pin = -1;

    if (room == "salon") {
        pin = LED_SALON;
    } else if (room == "cuisine") {
        pin = LED_CUISINE;
    } else if (room == "chambre") {
        pin = LED_CHAMBRE;
    } else {
        server.send(400, "text/plain", "Unknown room");
        return;
    }

    if (action == "on") {
        digitalWrite(pin, HIGH);
        server.send(200, "text/plain", room + " -> LED ON");
    } else if (action == "off") {
        digitalWrite(pin, LOW);
        server.send(200, "text/plain", room + " -> LED OFF");
    } else {
        server.send(400, "text/plain", "Unknown action: use on/off");
    }
}

void handleRoot() {
    server.send(
            200,
            "text/plain",
            "ESP32 Smart Home OK\n"
            "Use: /cmd?room=salon&object=led&action=on\n"
            "Rooms: salon, cuisine, chambre\n"
            "Objects: led/light/one/two/three\n"
            "Actions: on/off"
    );
}

void setup() {
    Serial.begin(115200);

    pinMode(LED_SALON, OUTPUT);
    pinMode(LED_CUISINE, OUTPUT);
    pinMode(LED_CHAMBRE, OUTPUT);

    digitalWrite(LED_SALON, LOW);
    digitalWrite(LED_CUISINE, LOW);
    digitalWrite(LED_CHAMBRE, LOW);

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