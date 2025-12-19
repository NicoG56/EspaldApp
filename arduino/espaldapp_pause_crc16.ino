#include <SoftwareSerial.h>

// Pines
#define PIN_TRIG   9
#define PIN_ECHO   10

#define PIN_LED_VERDE    6
#define PIN_LED_AMARILLO 7
#define PIN_LED_ROJO     8

// Botón pausa/reanudar (INPUT_PULLUP)
// Conexión: D4 <-> botón <-> GND
#define PIN_PAUSE 4

// Bluetooth
#define BT_RX 2
#define BT_TX 3
SoftwareSerial BT(BT_RX, BT_TX);

// Umbrales iniciales (ajustables por BT)
unsigned int umbralVerdeMM = 80;
unsigned int umbralRojoMM  = 120;
unsigned long tiempoUmbralMs = 30000;
bool alarmEnabled = true;

// Promedio de lecturas
const int N = 10;
unsigned int bufferDist[N];
int idx = 0;
bool bufferLleno = false;

// Estado de presencia/sentado
bool sentado = true;

// Pausa
bool pausado = false;
unsigned long lastPauseButtonChange = 0;
const unsigned long debounceMs = 30;

// Estado
unsigned int distPromMM = 0;
bool malaPostura = false;
bool alertaActiva = false;
unsigned long inicioMalaPosturaMs = 0;

// Reporte BT
unsigned long lastReportMs = 0;
const unsigned long reportIntervalMs = 500;

// =====================================================
// FUNCIONES DE SEGURIDAD - CRC16
// =====================================================

uint16_t calcularCRC16(const String& data) {
  uint16_t crc = 0xFFFF;
  for (unsigned int i = 0; i < data.length(); i++) {
    crc ^= ((uint16_t)data[i] << 8);
    for (int j = 0; j < 8; j++) {
      if (crc & 0x8000) {
        crc = (crc << 1) ^ 0x1021;
      } else {
        crc = crc << 1;
      }
    }
  }
  return crc & 0xFFFF;
}

String crcToHex(uint16_t crc) {
  String hex = String(crc, HEX);
  hex.toUpperCase();
  while (hex.length() < 4) {
    hex = "0" + hex;
  }
  return hex;
}

// =====================================================
// SETUP
// =====================================================

void setup() {
  pinMode(PIN_TRIG, OUTPUT);
  pinMode(PIN_ECHO, INPUT);

  pinMode(PIN_LED_VERDE, OUTPUT);
  pinMode(PIN_LED_AMARILLO, OUTPUT);
  pinMode(PIN_LED_ROJO, OUTPUT);

  pinMode(PIN_PAUSE, INPUT_PULLUP);

  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_AMARILLO, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);

  Serial.begin(9600);
  BT.begin(9600);

  Serial.println(F("Sistema de postura iniciado (UNO R3 CH340 + CRC16)."));
  Serial.println(F("Comandos BT: SET GREEN <mm>, SET RED <mm>, SET TIME <ms>, ALARM ON/OFF, PAUSE ON/OFF/TOGGLE, PING"));
  Serial.println(F("Pausa: boton en D4 (INPUT_PULLUP)"));
  Serial.print(F("Estado inicial PIN_PAUSE (D4), esperado HIGH sin presionar: "));
  Serial.println(digitalRead(PIN_PAUSE) == HIGH ? F("HIGH") : F("LOW"));
}

// =====================================================
// LOOP PRINCIPAL
// =====================================================

void loop() {
  leerBotonPausaDebounce();

  actualizarDistancia();

  // Si está pausado, no evaluar mala postura/alerta
  if (pausado) {
    inicioMalaPosturaMs = 0;
    malaPostura = false;
    alertaActiva = false;
  } else {
    bool enZonaAmarilla = (distPromMM > umbralVerdeMM && distPromMM <= umbralRojoMM);
    malaPostura = (sentado && distPromMM > umbralRojoMM && distPromMM > 0);

    if (malaPostura) {
      if (inicioMalaPosturaMs == 0) inicioMalaPosturaMs = millis();
      unsigned long duracion = millis() - inicioMalaPosturaMs;
      alertaActiva = (alarmEnabled && duracion >= tiempoUmbralMs);
    } else {
      inicioMalaPosturaMs = 0;
      alertaActiva = false;
    }

    actualizarLEDs(enZonaAmarilla);
  }

  // Bluetooth
  if (millis() - lastReportMs >= reportIntervalMs) {
    lastReportMs = millis();
    enviarEstadoBT();
  }
  recibirComandosBT();

  // Serial por USB (instrucciones)
  recibirComandosUSB();

  // Debug por USB (opcional)
  if (Serial.available()) {
    char c = Serial.read();
    if (c == 'r') enviarEstadoUSB();
    if (c == 'p') {
      Serial.print(F("PIN_PAUSE raw="));
      Serial.print(digitalRead(PIN_PAUSE) == HIGH ? F("HIGH") : F("LOW"));
      Serial.print(F(" | pausado="));
      Serial.println(pausado ? F("1") : F("0"));
    }
  }

  // Si está pausado, mantener LEDs apagados
  if (pausado) {
    digitalWrite(PIN_LED_VERDE, LOW);
    digitalWrite(PIN_LED_AMARILLO, LOW);
    digitalWrite(PIN_LED_ROJO, LOW);
  }
}

// =====================================================
// FUNCIONES
// =====================================================

void leerBotonPausaDebounce() {
  static bool lastRaw = HIGH;
  static bool lastStable = HIGH;
  static bool initialized = false;

  bool raw = digitalRead(PIN_PAUSE);

  // Evita togglear al inicio si el pin parte en LOW por cableado/orientación.
  if (!initialized) {
    lastRaw = raw;
    lastStable = raw;
    lastPauseButtonChange = millis();
    initialized = true;
    return;
  }

  if (raw != lastRaw) {
    lastPauseButtonChange = millis();
    lastRaw = raw;
  }

  if (millis() - lastPauseButtonChange > debounceMs) {
    if (lastStable != raw) {
      lastStable = raw;
      // INPUT_PULLUP: LOW = presionado
      if (raw == LOW) {
        pausado = !pausado;
        BT.println(pausado ? F("OK PAUSE ON") : F("OK PAUSE OFF"));
        Serial.println(pausado ? F("[BTN] PAUSE ON") : F("[BTN] PAUSE OFF"));
      }
    }
  }
}

void actualizarDistancia() {
  unsigned int dmm = medirDistanciaMM();
  if (dmm > 0) {
    bufferDist[idx] = dmm;
    idx = (idx + 1) % N;
    if (idx == 0) bufferLleno = true;
  }
  distPromMM = promedioDistancia();
}

unsigned int medirDistanciaMM() {
  digitalWrite(PIN_TRIG, LOW);
  delayMicroseconds(2);
  digitalWrite(PIN_TRIG, HIGH);
  delayMicroseconds(10);
  digitalWrite(PIN_TRIG, LOW);

  unsigned long dur = pulseIn(PIN_ECHO, HIGH, 30000UL);
  if (dur == 0) return 0;

  unsigned int mm = (unsigned int)(dur * 0.1715);
  if (mm < 50 || mm > 1000) return 0;
  return mm;
}

unsigned int promedioDistancia() {
  if (!bufferLleno && idx == 0) return 0;
  int count = bufferLleno ? N : idx;
  unsigned long sum = 0;
  int valid = 0;
  for (int i = 0; i < count; i++) {
    if (bufferDist[i] > 0) {
      sum += bufferDist[i];
      valid++;
    }
  }
  if (valid == 0) return 0;
  return (unsigned int)(sum / valid);
}

void actualizarLEDs(bool enZonaAmarilla) {
  digitalWrite(PIN_LED_VERDE, LOW);
  digitalWrite(PIN_LED_AMARILLO, LOW);
  digitalWrite(PIN_LED_ROJO, LOW);

  if (!sentado || distPromMM == 0) {
    return;
  }

  if (alertaActiva) {
    digitalWrite(PIN_LED_ROJO, HIGH);
    return;
  }

  if (malaPostura) {
    digitalWrite(PIN_LED_ROJO, HIGH);
  } else if (enZonaAmarilla) {
    digitalWrite(PIN_LED_AMARILLO, HIGH);
  } else {
    digitalWrite(PIN_LED_VERDE, HIGH);
  }
}

/**
 * Envía estado por Bluetooth CON CRC16
 * Formato: DIST:x,SENT:x,BAD:x,ALR:x,GREEN:x,RED:x,PAUS:x,CRC:XXXX
 */
void enviarEstadoBT() {
  String msg = "DIST:";
  msg += distPromMM;
  msg += ",SENT:";
  msg += sentado ? 1 : 0;
  msg += ",BAD:";
  msg += malaPostura ? 1 : 0;
  msg += ",ALR:";
  msg += alertaActiva ? 1 : 0;
  msg += ",GREEN:";
  msg += umbralVerdeMM;
  msg += ",RED:";
  msg += umbralRojoMM;
  msg += ",PAUS:";
  msg += pausado ? 1 : 0;

  uint16_t crc = calcularCRC16(msg);
  msg += ",CRC:";
  msg += crcToHex(crc);

  BT.println(msg);
}

void enviarEstadoUSB() {
  Serial.print(F("Dist(mm)="));
  Serial.print(distPromMM);
  Serial.print(F(" Pausado="));
  Serial.print(pausado);
  Serial.print(F(" Sentado="));
  Serial.print(sentado);
  Serial.print(F(" MalaPostura="));
  Serial.print(malaPostura);
  Serial.print(F(" Alerta="));
  Serial.print(alertaActiva);
  Serial.print(F(" UmbralVerde="));
  Serial.print(umbralVerdeMM);
  Serial.print(F(" UmbralRojo="));
  Serial.println(umbralRojoMM);
}

void recibirComandosBT() {
  static String cmd;
  while (BT.available()) {
    char c = BT.read();
    if (c == '\n' || c == '\r') {
      procesarCmd(cmd);
      cmd = "";
    } else {
      cmd += c;
      if (cmd.length() > 128) cmd = "";
    }
  }
}

void recibirComandosUSB() {
  static String cmd;
  while (Serial.available()) {
    char c = Serial.read();
    // Mantener atajos de debug: 'r' y 'p'
    if (cmd.length() == 0 && (c == 'r' || c == 'p')) {
      // Estos ya se manejan en el bloque de debug del loop
      return;
    }
    if (c == '\n' || c == '\r') {
      if (cmd.length() > 0) {
        procesarCmd(cmd);
        cmd = "";
      }
    } else {
      cmd += c;
      if (cmd.length() > 128) cmd = "";
    }
  }
}

void procesarCmd(const String& s) {
  String up = s;
  up.trim();
  up.toUpperCase();

  int crcPos = up.indexOf(",CRC:");
  if (crcPos > 0) {
    up = up.substring(0, crcPos);
  }

  if (up.startsWith("PING")) {
    BT.println(F("PONG"));
    Serial.println(F("PONG"));
    return;
  }

  if (up.startsWith("PAUSE TOGGLE") || up == "PAUSE") {
    pausado = !pausado;
    BT.println(pausado ? F("OK PAUSE ON") : F("OK PAUSE OFF"));
    Serial.println(pausado ? F("OK PAUSE ON") : F("OK PAUSE OFF"));
    return;
  }
  if (up.startsWith("PAUSE ON")) {
    pausado = true;
    BT.println(F("OK PAUSE ON"));
    Serial.println(F("OK PAUSE ON"));
    return;
  }
  if (up.startsWith("PAUSE OFF")) {
    pausado = false;
    BT.println(F("OK PAUSE OFF"));
    Serial.println(F("OK PAUSE OFF"));
    return;
  }

  if (up.startsWith("SET GREEN")) {
    long v = parseNumber(up);
    if (v >= 60 && v <= 200) {
      umbralVerdeMM = (unsigned int)v;
      BT.print(F("OK GREEN "));
      BT.println(umbralVerdeMM);
      Serial.print(F("OK GREEN "));
      Serial.println(umbralVerdeMM);
    } else {
      BT.println(F("ERR GREEN RANGE 60-200"));
      Serial.println(F("ERR GREEN RANGE 60-200"));
    }
    return;
  }
  if (up.startsWith("SET RED")) {
    long v = parseNumber(up);
    if (v >= 80 && v <= 400) {
      umbralRojoMM = (unsigned int)v;
      BT.print(F("OK RED "));
      BT.println(umbralRojoMM);
      Serial.print(F("OK RED "));
      Serial.println(umbralRojoMM);
    } else {
      BT.println(F("ERR RED RANGE 80-400"));
      Serial.println(F("ERR RED RANGE 80-400"));
    }
    return;
  }
  if (up.startsWith("SET TIME")) {
    long v = parseNumber(up);
    if (v >= 5000 && v <= 300000) {
      tiempoUmbralMs = (unsigned long)v;
      BT.print(F("OK TIME "));
      BT.println(tiempoUmbralMs);
      Serial.print(F("OK TIME "));
      Serial.println(tiempoUmbralMs);
    } else {
      BT.println(F("ERR TIME RANGE 5000-300000"));
      Serial.println(F("ERR TIME RANGE 5000-300000"));
    }
    return;
  }
  if (up.startsWith("ALARM ON")) {
    alarmEnabled = true;
    BT.println(F("OK ALARM ON"));
    Serial.println(F("OK ALARM ON"));
    return;
  }
  if (up.startsWith("ALARM OFF")) {
    alarmEnabled = false;
    BT.println(F("OK ALARM OFF"));
    Serial.println(F("OK ALARM OFF"));
    return;
  }

  BT.println(F("ERR CMD"));
  Serial.println(F("ERR CMD"));
}

long parseNumber(const String& s) {
  int i = s.lastIndexOf(' ');
  if (i >= 0 && i + 1 < (int)s.length()) {
    return s.substring(i + 1).toInt();
  }
  return -1;
}
