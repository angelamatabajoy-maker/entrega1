import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Server {

    // ==================================================================
    // PROTOCOLO DE COMUNICACIÓN (línea a línea, codificado en UTF-8)
    //
    // Registro (la primera línea que envía cada cliente es su nombre):
    // Cliente -> nombre de usuario
    // Servidor -> "OK" | "ERROR:nombre_invalido" | "ERROR:usuario_ocupado"
    // El cliente puede cancelar el registro enviando "/salir".
    //
    // Mensajes que envía el servidor:
    // "MENSAJE:<usuario>:<texto>" -> mensaje difundido a todos
    // "PRIVADO:<usuario>:<texto>" -> mensaje privado entregado al destinatario
    // "PRIVADO_ENVIADO:<usuario>:<texto>" -> copia al remitente de su privado
    // "USUARIOS:<u1>,<u2>,..." -> lista de usuarios conectados
    // "INFO:<texto>" -> aviso del sistema
    //
    // Comandos que entiende el servidor:
    // "/usuarios" -> enviar la lista de usuarios solo al remitente
    // "PRIVADO:<usuario>:<texto>" -> entregar el mensaje solo a ese usuario
    // "/salir" -> desconectar al cliente del chat
    // "chao" (o "PRIVADO:<usuario>:chao") -> se entrega y el usuario abandona el chat
    //
    // Cualquier otra línea se difunde como mensaje de chat.
    // ==================================================================

    // 1. Atributos y configuración
    private static final int PUERTO_DEFECTO = 9000;

    // Usuarios conectados: nombre de usuario -> hilo que lo atiende.
    // ConcurrentHashMap es seguro ante hilos: varios clientes pueden
    // registrarse, salir y difundir mensajes al mismo tiempo.
    private static final Map<String, ClientHandler> usuariosConectados = new ConcurrentHashMap<>();

    // 2. Punto de entrada
    public static void main(String[] args) {
        int puerto = PUERTO_DEFECTO;

        // El puerto se puede pasar como argumento: java Server 9000
        if (args.length > 0) {
            try {
                puerto = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("[Servidor] Puerto inválido: " + args[0]
                        + ". Se usará el puerto " + PUERTO_DEFECTO);
            }
        }

        iniciarServidor(puerto);
    }

    // 3. Inicialización del servidor
    private static void iniciarServidor(int puerto) {
        // try-with-resources: el ServerSocket se cierra solo al terminar
        try (ServerSocket serverSocket = new ServerSocket(puerto)) {
            System.out.println("[Servidor] Chat iniciado en el puerto " + puerto);
            System.out.println("[Servidor] Esperando conexiones... (Ctrl+C para detener)");
            aceptarConexiones(serverSocket);
        } catch (IOException e) {
            System.err.println("[Servidor] No se pudo iniciar el servidor: " + e.getMessage());
            System.exit(1);
        }
    }

    // 4. Gestión de conexiones
    private static void aceptarConexiones(ServerSocket serverSocket) {
        // Bucle infinito: el servidor vive aceptando clientes mientras no se cierre
        while (!serverSocket.isClosed()) {
            try {
                // accept() se bloquea hasta que llegue un cliente nuevo
                Socket socket = serverSocket.accept();
                System.out.println("[Servidor] Conexión aceptada desde "
                        + socket.getRemoteSocketAddress());
                atenderCliente(socket);
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    System.err.println("[Servidor] Error al aceptar una conexión: "
                            + e.getMessage());
                }
            }
        }
    }

    // 5. Gestión individual de clientes
    private static void atenderCliente(Socket socket) {
        // Cada cliente se atiende en su propio hilo para que el servidorF
        // pueda seguir aceptando conexiones mientras atiende a los demás.
        ClientHandler handler = new ClientHandler(socket);
        new Thread(handler, "cliente-" + socket.getPort()).start();
    }

    // 6. Gestión de usuarios
    private static boolean registrarUsuario(String username, ClientHandler handler) {
        // Rechazar nombres nulos, vacíos o con caracteres reservados del protocolo
        if (username == null || username.isBlank()
                || username.contains(":") || username.contains(",")) {
            return false;
        }
        // putIfAbsent registra el nombre solo si está libre (operación atómica):
        // devuelve null si el nombre no existía, o el valor actual si ya estaba ocupado
        return usuariosConectados.putIfAbsent(username, handler) == null;
    }

    private static void eliminarUsuario(String username) {
        if (username != null) {
            usuariosConectados.remove(username);
        }
    }

    private static void enviarListaUsuarios(ClientHandler destinatario) {
        String lista = "USUARIOS:" + String.join(",", usuariosConectados.keySet());
        if (destinatario == null) {
            // null = difundir la lista a todos los usuarios conectados
            enviarMensaje(lista, null);
        } else {
            // enviar la lista solo al cliente que la pidió
            destinatario.enviar(lista);
        }
    }

    // 7. Gestión de mensajes
    private static void procesarMensaje(String mensaje, ClientHandler remitente) {
        // Dar formato al mensaje y difundirlo a todos los usuarios,
        // incluido el remitente (así confirma que el mensaje llegó)
        String mensajeFormateado = "MENSAJE:" + remitente.username + ":" + mensaje;
        enviarMensaje(mensajeFormateado, null);
    }

    // Entrega un mensaje solo al destinatario elegido y confirma al remitente:
    // así la comunicación entre dos usuarios es bidireccional sin que el
    // resto del chat vea la conversación
    private static void procesarMensajePrivado(String destinatario, String texto,
            ClientHandler remitente) {
        if (destinatario.equals(remitente.username)) {
            remitente.enviar("INFO:no puedes enviarte mensajes privados a ti mismo");
            return;
        }
        ClientHandler destino = usuariosConectados.get(destinatario);
        if (destino == null) {
            remitente.enviar("INFO:el usuario '" + destinatario + "' no está conectado");
            return;
        }
        // al destinatario le llega el mensaje con el nombre del remitente...
        destino.enviar("PRIVADO:" + remitente.username + ":" + texto);
        // ...y al remitente una copia para confirmar que se entregó
        remitente.enviar("PRIVADO_ENVIADO:" + destinatario + ":" + texto);
    }

    private static void enviarMensaje(String mensaje, ClientHandler excluido) {
        // excluido = cliente que no debe recibir el mensaje (null = enviar a todos)
        for (ClientHandler handler : usuariosConectados.values()) {
            if (handler != excluido) {
                handler.enviar(mensaje);
            }
        }
    }

    // ==================================================================
    // Hilo que atiende a un cliente concreto mientras permanezca conectado
    // ==================================================================
    static class ClientHandler implements Runnable {

        // 1. Atributos de conexión
        private final Socket socket;
        private BufferedReader input;
        private PrintWriter output;

        // 2. Información del cliente
        private String username;
        private boolean conectado = true;

        // 3. Constructor
        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        // 4. Punto de entrada del hilo
        @Override
        public void run() {
            try {
                // Inicializar canales de comunicación
                input = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                output = new PrintWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                        true); // true = autoFlush: envía cada línea inmediatamente

                // Registrar usuario: la primera línea del cliente es su nombre
                while (true) {
                    String nombre = input.readLine();
                    if (nombre == null) {
                        return; // el cliente cerró la conexión antes de registrarse
                    }
                    nombre = nombre.trim();

                    if (nombre.equalsIgnoreCase("/salir")) {
                        return; // el cliente canceló el registro
                    }
                    if (nombre.isEmpty() || nombre.contains(":") || nombre.contains(",")) {
                        enviar("ERROR:nombre_invalido");
                    } else if (registrarUsuario(nombre, this)) {
                        username = nombre;
                        enviar("OK");
                        System.out.println("[Servidor] '" + username + "' se ha conectado. "
                                + "Usuarios en línea: " + usuariosConectados.size());
                        // Avisar a todos y actualizar las listas de usuarios
                        enviarMensaje("INFO:" + username + " se ha conectado", null);
                        enviarListaUsuarios(null);
                        break;
                    } else {
                        enviar("ERROR:usuario_ocupado");
                    }
                }

                // Escuchar mensajes hasta que el cliente se desconecte
                escucharMensajes();
            } catch (IOException e) {
                // La conexión se perdió de forma abrupta (corte de red, cierre forzado...)
                System.err.println("[Servidor] Conexión perdida con "
                        + (username != null ? username : socket.getRemoteSocketAddress())
                        + ": " + e.getMessage());
            } finally {
                // Gestionar desconexión
                cerrarConexion();
            }
        }

        // 5. Métodos de comunicación
        private void escucharMensajes() throws IOException {
            String mensaje;
            // readLine() devuelve null cuando el cliente cierra la conexión
            while (conectado && (mensaje = input.readLine()) != null) {
                if (!mensaje.isBlank()) {
                    procesarMensaje(mensaje.trim());
                }
            }
        }

        private void procesarMensaje(String message) {
            if (message.equals("/usuarios")) {
                // Pedir al servidor la lista de usuarios solo para este cliente
                enviarListaUsuarios(this);
            } else if (message.equals("/salir")) {
                // Marcar la desconexión: el bucle de escucha terminará y el
                // bloque finally de run() cerrará la conexión y avisará al resto
                conectado = false;
            } else if (message.startsWith("PRIVADO:")) {
                // Mensaje dirigido a un solo usuario: "PRIVADO:<destinatario>:<texto>"
                String[] partes = message.split(":", 3);
                if (partes.length == 3) {
                    Server.procesarMensajePrivado(partes[1], partes[2], this);
                    marcarSiChao(partes[2]);
                } else {
                    enviar("INFO:uso: PRIVADO:<usuario>:<mensaje>");
                }
            } else if (message.startsWith("/")) {
                // Comando no reconocido: avisar solo a este cliente
                enviar("INFO:comando desconocido: " + message);
            } else {
                // Mensaje normal: delegar en el servidor para difundirlo a todos
                Server.procesarMensaje(message, this);
                marcarSiChao(message);
            }
        }

        // "chao" se entrega como un mensaje más y además marca que el usuario
        // abandonó el chat: el bucle de escucha termina y se cierra la conexión
        private void marcarSiChao(String texto) {
            if (texto.trim().equalsIgnoreCase("chao")) {
                System.out.println("[Servidor] El usuario '" + username + "' abandonó el chat");
                conectado = false;
            }
        }

        // Envía una línea al cliente conectado a este hilo
        private void enviar(String mensaje) {
            if (output != null) {
                output.println(mensaje);
            }
        }

        // 6. Cierre de conexión
        public void cerrarConexion() {
            // Método idempotente: se puede llamar más de una vez sin repetir avisos
            if (username != null) {
                // 1. Sacar al usuario de la lista de conectados
                eliminarUsuario(username);
                System.out.println("[Servidor] '" + username + "' se ha desconectado. "
                        + "Usuarios en línea: " + usuariosConectados.size());
                // 2. Avisar al resto de usuarios y actualizar sus listas
                enviarMensaje("INFO:" + username + " se ha desconectado", null);
                enviarListaUsuarios(null);
                username = null;
            }
            // 3. Cerrar los canales y el socket
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
                if (!socket.isClosed()) {
                    socket.close();
                }
            } catch (IOException e) {
                // ignorar errores al cerrar
            }
        }
    }
}
