import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Cliente {
    public static void main(String[] args) throws IOException {
        Scanner sc = new Scanner(System.in);
        System.out.print("IP del servidor (ej. 127.0.0.1): ");
        String ip = sc.nextLine();
        System.out.print("Puerto de la conexion (ej. 59420): ");
        int puerto = Integer.parseInt(sc.nextLine());

        try (Socket s = new Socket(ip, puerto);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {

            // Hilo que imprime todo lo que llega del servidor
            Thread lector = new Thread(() -> {
                try {
                    String l;
                    while ((l = in.readLine()) != null) System.out.println(l);
                } catch (IOException e) { }
                System.out.println("Conexion cerrada.");
            });
            lector.setDaemon(true);
            lector.start();

            // Hilo principal: envia lo que escribe el usuario
            while (sc.hasNextLine()) {
                String linea = sc.nextLine();
                out.println(linea);
                if (linea.equalsIgnoreCase("chao")) break;
            }
        }
    }
}