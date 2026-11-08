import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class Servidor {
    static final int PUERTO = 12345;
    // nombre de usuario -> canal de salida hacia ese cliente
    static final Map<String, PrintWriter> clientes = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        try (ServerSocket ss = new ServerSocket(PUERTO)) {
            System.out.println("Servidor iniciado y contestando OK (puerto " + PUERTO + ")");
            while (true) {
                Socket s = ss.accept();               // espera a un cliente
                new Thread(() -> atender(s)).start(); // un hilo por cliente
            }
        }
    }

    static void atender(Socket s) {
        String nombre = null;
        boolean registrado = false;
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

            out.println("Escriba su nombre:");
            nombre = in.readLine();
            if (nombre == null || nombre.isBlank() || clientes.containsKey(nombre)) {
                out.println("Nombre invalido o ya en uso.");
                return;
            }
            clientes.put(nombre, out);
            registrado = true;
            System.out.println("Usuario \"" + nombre + "\" conectado");

            out.println("Conectados: " + clientes.keySet());
            out.println("Use  usuario: mensaje  |  'lista' para ver conectados  |  'chao' para salir");

            String linea;
            while ((linea = in.readLine()) != null) {
                if (linea.equalsIgnoreCase("chao")) break;
                if (linea.equalsIgnoreCase("lista")) {
                    out.println("Conectados: " + clientes.keySet());
                    continue;
                }
                int i = linea.indexOf(':');
                if (i < 0) {
                    out.println("Formato: usuario: mensaje");
                    continue;
                }
                String dest = linea.substring(0, i).trim();
                String msg = linea.substring(i + 1).trim();
                PrintWriter o = clientes.get(dest);
                if (o == null) {
                    out.println("Usuario no conectado: " + dest);
                } else {
                    String texto = nombre + "--> " + msg;
                    o.println(texto);   // al destinatario
                    out.println(texto); // eco para quien escribe
                }
            }
        } catch (IOException e) {
            // el cliente se desconecto de golpe
        } finally {
            if (registrado) {
                clientes.remove(nombre);
                System.out.println("El usuario \"" + nombre + "\" abandonó");
            }
            try { s.close(); } catch (IOException e) { }
        }
    }
}