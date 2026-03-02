#include <WiFi.h>
#include <WebServer.h>

const char* ssid = "ESP32_SmartHome";
const char* password = "12345678"; // min 8 chars

WebServer server(80);

const int LED_PIN = 2; // change si besoin (ex: 4, 5, 18...)

void handleCmd() {
  if (!server.hasArg("value")) {
    server.send(400, "text/plain", "Missing query param: value");
    return;
  }

  String cmd = server.arg("value");
  cmd.toLowerCase();

  Serial.print("[HTTP] cmd = ");
  Serial.println(cmd);

  if (cmd == "on") {
    digitalWrite(LED_PIN, HIGH);
    server.send(200, "text/plain", "LED ON");
  } else if (cmd == "off") {
    digitalWrite(LED_PIN, LOW);
    server.send(200, "text/plain", "LED OFF");
  } else {
    server.send(400, "text/plain", "Unknown command. Use on/off");
  }
}

void handleRoot() {
  server.send(200, "text/plain",
              "ESP32 OK. Try /cmd?value=on or /cmd?value=off");
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, LOW);

  // 1) Hotspot (SoftAP)
  WiFi.mode(WIFI_AP);
  bool ok = WiFi.softAP(ssid, password);

  Serial.println();
  Serial.println(ok ? "[WiFi] SoftAP started" : "[WiFi] SoftAP failed");
  Serial.print("[WiFi] SSID: ");
  Serial.println(ssid);
  Serial.print("[WiFi] AP IP: ");
  Serial.println(WiFi.softAPIP()); // normalement 192.168.4.1

  // 2) Serveur HTTP
  server.on("/", handleRoot);
  server.on("/cmd", handleCmd);
  server.begin();
  Serial.println("[HTTP] server started on port 80");
}

void loop() {
  server.handleClient();
}