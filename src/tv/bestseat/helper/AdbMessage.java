package tv.bestseat.helper;

/** Eine adb-Nachricht. Eigene Datei statt innerer Klasse, weil d8 an inneren Klassen scheitert. */
final class AdbMessage {
    int command;
    int arg0;
    int arg1;
    byte[] data;
}
