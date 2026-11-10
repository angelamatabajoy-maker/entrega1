import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Cliente {

    // ==================================================================
    // PROTOCOLO con el servidor (implementado en Server.java):
    // Registro: el cliente envía su nombre y el servidor responde
    // "OK", "ERROR:nombre_invalido" o "ERROR:usuario_ocupado"
    // Recibe: "MENSAJE:<usuario>:<texto>" (difusión),
    // "PRIVADO:<usuario>:<texto>" (privado que llega),
    // "PRIVADO_ENVIADO:<usuario>:<texto>" (confirmación),
    // "USUARIOS:<u1>,<u2>,..." e "INFO:<texto>"
    // Envía: texto normal (se difunde), "PRIVADO:<usuario>:<texto>"
    // (cuando hay destinatario elegido), "/usuarios" (pedir la
    // lista) y "/salir" (desconectarse)
    //
    // Comandos locales del cliente (no viajan al servidor):
    // /seleccionar -> lista numerada de usuarios para elegir a quién
    // hablar en privado (0 = todos)
    // /todos -> volver al modo difusión
    //
    // Dos hilos: el principal lee el teclado y envía; otro escucha al
    // servidor y muestra lo que llega. "conectado" y "destinatario" son
    // volatile porque los usan ambos hilos.
    // ==================================================================

    // 1. Configuración
    private String host;
    private int puerto;

    // 2. Conexión
    private Socket socket;
    private BufferedReader input; // lee las líneas que envía el servidor
    private PrintWriter output; // envía líneas al servidor
    private BufferedReader teclado; // lee lo que teclea el usuario

    // Estado compartido por los dos hilos del cliente
    private String username;
    private volatile boolean conectado = true;
    private boolean cerrado = false;

    // Copia local de los usuarios conectados, refrescada con cada lista
    // "USUARIOS:" que difunde el servidor (alguien entra o sale del chat).
    // La usa /seleccionar sin tener que preguntar al servidor
    private final List<String> usuarios = Collections.synchronizedList(new ArrayList<>());

    // Destinatario de los mensajes: null = modo difusión (todos),
    // o el nombre del usuario con el que se habla en privado
    private volatile String destinatario = null;

    // Para sugerir una sola vez cómo responder a un privado recibido
    private boolean avisoRespuestaPrivada = false;

    // Constructor que recibe el host y el puerto personalizados
    public Cliente(String host, int puerto) {
        this.host = host;
        this.puerto = puerto;
    }

    // 3. Inicialización
    public boolean conectar() {
        try {
            socket = new Socket(host, puerto);
            input = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            output = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    true); // true = autoFlush: cada línea se envía inmediatamente
            teclado = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            System.out.println("[Cliente] Conectado al servidor " + host + ":" + puerto);
            return true;
        } catch (IOException e) {
            System.err.println("[Cliente] No se pudo conectar con " + host + ":" + puerto
                    + " (¿está el servidor activo?)");
            return false;
        }
    }

    // 4. Identificación
    private boolean registrarUsuario() {
        System.out.println("[Cliente] Escribe tu nombre para entrar"
                + " (o /salir para cancelar).");
        try {
            while (true) {
                System.out.print("Elige tu nombre de usuario: ");
                String nombre = teclado.readLine();
                if (nombre == null) {
                    return false; // fin de la entrada estándar
                }
                nombre = nombre.trim();

                if (nombre.equalsIgnoreCase("/salir")) {
                    System.out.println("[Cliente] Registro cancelado.");
                    return false;
                }
                // Validación local para no gastar una consulta al servidor;
                // el servidor repite la validación por seguridad
                if (nombre.isEmpty()) {
                    System.out.println("[Cliente] Nombre no válido: no puede estar vacío");
                    continue;
                }

                output.println(nombre);
                String respuesta = input.readLine();
                if (respuesta == null) {
                    System.out.println("[Cliente] El servidor cerró la conexión"
                            + " durante el registro.");
                    return false;
                }

                if (respuesta.equals("OK")) {
                    username = nombre;
                    System.out.println("[Cliente] Registrado como '" + username
                            + "'. ¡Bienvenido al chat!");
                    // Tras el "OK" el servidor envía el aviso de conexión y la
                    // primera lista de usuarios: consumirlas aquí (antes de
                    // arrancar el hilo de escucha) garantiza que /seleccionar
                    // tenga la lista de usuarios lista desde el primer momento
                    for (int i = 0; i < 2; i++) {
                        String linea = input.readLine();
                        if (linea == null) {
                            break;
                        }
                        mostrar(linea);
                    }
                    return true;
                }
                if (respuesta.equals("ERROR:usuario_ocupado")) {
                    System.out.println("[Cliente] Ese nombre ya está en uso. Prueba con otro.");
                } else if (respuesta.equals("ERROR:nombre_invalido")) {
                    System.out.println("[Cliente] El servidor rechazó ese nombre. Prueba otro.");
                } else {
                    System.out.println("[Cliente] Respuesta desconocida: " + respuesta);
                    return false;
                }
            }
        } catch (IOException e) {
            System.err.println("[Cliente] Error de comunicación durante el registro: "
                    + e.getMessage());
            return false;
        }
    }

    // 5. Comunicación
    // Se ejecuta en su propio hilo: muestra todo lo que llega del servidor
    private void escucharMensajes() {
        try {
            String linea;
            // readLine() devuelve null cuando el servidor cierra la conexión
            while (conectado && (linea = input.readLine()) != null) {
                if (conectado) { // no mostrar nada después de haber salido
                    mostrar(linea);
                }
            }
        } catch (IOException e) {
            // silencioso si el cierre lo provocó este cliente; si lo provocó
            // el servidor, se informa justo después
        }
        if (conectado) {
            conectado = false;
            System.out.println();
            System.out.println("[Cliente] El servidor ha cerrado la conexión.");
            cerrarConexion();
            // el hilo principal puede estar bloqueado esperando el teclado:
            // hay que terminar la aplicación desde aquí
            System.exit(0);
        }
    }

    // Traduce una línea del protocolo del servidor a algo legible en consola
    private void mostrar(String linea) {
        if (linea.startsWith("MENSAJE:")) {
            String[] partes = linea.split(":", 3); // [MENSAJE, usuario, texto]
            if (partes.length == 3) {
                System.out.println("[" + partes[1] + "] " + partes[2]);
            }
        } else if (linea.startsWith("USUARIOS:")) {
            // cada lista que difunde el servidor refresca la copia local
            // que usa /seleccionar para elegir destinatario
            actualizarUsuarios(linea.substring("USUARIOS:".length()));
            if (usuarios.isEmpty()) {
                System.out.println("[Cliente] No hay usuarios conectados.");
            } else {
                System.out.println("[Cliente] Usuarios conectados: "
                        + String.join(", ", usuarios));
            }
        } else if (linea.startsWith("PRIVADO:")) {
            // mensaje privado que me envía otro usuario
            String[] partes = linea.split(":", 3); // [PRIVADO, remitente, texto]
            if (partes.length == 3) {
                System.out.println("[" + partes[1] + " (privado)] " + partes[2]);
            }
            // sugerir una sola vez cómo responder si estoy en modo difusión
            if (!avisoRespuestaPrivada && destinatario == null) {
                avisoRespuestaPrivada = true;
                System.out.println("[Cliente] Para responderle en privado usa /seleccionar.");
            }
        } else if (linea.startsWith("PRIVADO_ENVIADO:")) {
            // confirmación del privado que acabo de enviar
            String[] partes = linea.split(":", 3); // [PRIVADO_ENVIADO, destinatario, texto]
            if (partes.length == 3) {
                System.out.println("[tú -> " + partes[1] + "] " + partes[2]);
            }
        } else if (linea.startsWith("INFO:")) {
            System.out.println("[Servidor] " + linea.substring("INFO:".length()));
        } else {
            System.out.println("[Cliente] " + linea);
        }
    }

    // Refresca la copia local de usuarios con la última lista del servidor
    private void actualizarUsuarios(String nombres) {
        synchronized (usuarios) {
            usuarios.clear();
            if (!nombres.isEmpty()) {
                for (String nombre : nombres.split(",")) {
                    usuarios.add(nombre);
                }
            }
        }
        // si el destinatario elegido ya no está conectado, volver a difusión
        if (destinatario != null && !usuarios.contains(destinatario)) {
            System.out.println("[Cliente] '" + destinatario + "' ya no está conectado:"
                    + " vuelves a hablar con todos.");
            destinatario = null;
        }
    }

    // /seleccionar: muestra la lista numerada de usuarios y el usuario
    // elige con quién hablar en privado (0 = volver a hablar con todos)
    private void seleccionarDestinatario() {
        // copia de la lista sin el propio usuario: no tiene sentido
        // hablar en privado consigo mismo
        List<String> disponibles = new ArrayList<>();
        synchronized (usuarios) {
            for (String nombre : usuarios) {
                if (!nombre.equals(username)) {
                    disponibles.add(nombre);
                }
            }
        }
        if (disponibles.isEmpty()) {
            System.out.println("[Cliente] No hay otros usuarios conectados.");
            return;
        }
        System.out.println("[Cliente] ¿Con quién quieres hablar?");
        for (int i = 0; i < disponibles.size(); i++) {
            System.out.println("  " + (i + 1) + ". " + disponibles.get(i));
        }
        System.out.println("  0. Todos (hablar con todo el chat)");
        System.out.print("Elige un número: ");
        System.out.flush();
        String eleccion;
        try {
            eleccion = teclado.readLine();
        } catch (IOException e) {
            return;
        }
        if (eleccion == null || eleccion.trim().equalsIgnoreCase("/salir")) {
            return; // cancelar la selección sin cambiar el destinatario
        }
        int numero;
        try {
            numero = Integer.parseInt(eleccion.trim());
        } catch (NumberFormatException e) {
            System.out.println("[Cliente] Debes escribir un número. No se cambió el destinatario.");
            return;
        }
        if (numero == 0) {
            destinatario = null;
            System.out.println("[Cliente] Hablas con todos los usuarios.");
        } else if (numero >= 1 && numero <= disponibles.size()) {
            destinatario = disponibles.get(numero - 1);
            System.out.println("[Cliente] Hablas en privado con '" + destinatario + "'."
                    + " (/todos para volver a difundir)");
        } else {
            System.out.println("[Cliente] Número fuera de la lista. No se cambió el destinatario.");
        }
    }

    // Se ejecuta en el hilo principal: lee el teclado y envía cada línea
    private void enviarMensajes() {
        System.out.println("[Cliente] Escribe un mensaje y pulsa Enter para enviarlo.");
        System.out.println("[Cliente] Comandos: /usuarios, /seleccionar, /todos, /salir"
                + " (o escribe chao para despedirte y salir)");
        try {
            String linea;
            while (conectado && (linea = teclado.readLine()) != null) {
                linea = linea.trim();
                if (linea.isEmpty()) {
                    continue; // no enviar líneas vacías
                }
                // /seleccionar y /todos se atienden aquí mismo, en el cliente:
                // el servidor no necesita saber con quién hablas
                if (linea.equals("/seleccionar")) {
                    seleccionarDestinatario();
                    continue;
                }
                if (linea.equals("/todos")) {
                    destinatario = null;
                    System.out.println("[Cliente] Hablas con todos los usuarios.");
                    continue;
                }
                // los demás comandos (/usuarios, /salir, ...) van al servidor tal cual
                if (linea.startsWith("/")) {
                    output.println(linea);
                    if (linea.equals("/salir")) {
                        conectado = false; // el cierre lo completa cerrarConexion()
                        break;
                    }
                    continue;
                }
                // texto normal: privado si hay destinatario elegido, o difusión
                if (destinatario != null) {
                    output.println("PRIVADO:" + destinatario + ":" + linea);
                } else {
                    output.println(linea);
                }
                // "chao" se despide del interlocutor y abandona el chat
                // (el servidor marca al usuario como que abandonó)
                if (linea.equalsIgnoreCase("chao")) {
                    System.out.println("[tú" + (destinatario != null ? " -> " + destinatario : "")
                            + "] " + linea + "  (abandonas el chat)");
                    conectado = false;
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Cliente] Error al leer el teclado: " + e.getMessage());
        }
        cerrarConexion();
    }

    // 6. Desconexión
    // synchronized porque pueden llamarlo los dos hilos (teclado y escucha);
    // el flag "cerrado" lo hace idempotente para no repetir avisos
    private synchronized void cerrarConexion() {
        if (cerrado) {
            return;
        }
        cerrado = true;
        conectado = false;
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException e) {
            // ignorar errores al cerrar
        }
        if (output != null) {
            output.close();
        }
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            // ignorar errores al cerrar
        }
        System.out.println("[Cliente] Desconectado del servidor.");
    }

    // 7. Punto de entrada
    public static void main(String[] args) {
        // Valores por defecto
        String host = "127.0.0.1";
        int puerto = 9000;

        // Si el usuario pasa argumentos: java Cliente <host> <puerto>
        if (args.length > 0) {
            host = args[0]; // El primer argumento es la IP o el túnel
        }
        if (args.length > 1) {
            try {
                puerto = Integer.parseInt(args[1]); // El segundo argumento es el puerto
            } catch (NumberFormatException e) {
                System.err.println("[Cliente] Puerto inválido: " + args[1] + ". Usando puerto por defecto: 9000");
            }
        }

        // Pasamos el host y el puerto al constructor o a la clase Cliente
        Cliente cliente = new Cliente(host, puerto);

        if (!cliente.conectar()) {
            return;
        }

        if (!cliente.registrarUsuario()) {
            cliente.cerrarConexion();
            return;
        }

        Thread hiloEscucha = new Thread(() -> cliente.escucharMensajes(), "escucha-servidor");
        hiloEscucha.start();

        cliente.enviarMensajes();
        System.exit(0);
    }
}
