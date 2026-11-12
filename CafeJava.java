import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.BooleanSupplier;

public class CafeJava {
    static final Random RNG = new Random();
    static final int DIAS = 5;

    // The whole menu lives in one list. Every other part of the game reads from it,
    // so changing the menu means editing only this block.
    // Each recipe is a Map of ingredient name -> units needed. Using Map.of means a typo
    // in the structure fails at compile time instead of crashing during the game.
    static final List<Producto> MENU = List.of(
        new Producto("Croissant de pistacho", 4.50, Tipo.COMIDA, Map.of("Pasteles", 1)),
        new Producto("Sando japones de huevo", 5.50, Tipo.COMIDA, Map.of("Pan", 2, "Huevos", 1)),
        new Producto("Tostada dulce con sesamo", 4.00, Tipo.COMIDA, Map.of("Pan", 1)),
        new Producto("Tostada hot honey", 4.50, Tipo.COMIDA, Map.of("Pan", 1, "Miel", 1)),
        new Producto("Cookie de miso y caramelo", 3.50, Tipo.COMIDA, Map.of("Pasteles", 1)),
        new Producto("Cafe", 3.00, Tipo.BEBIDA, Map.of("Cafe", 1)),
        new Producto("Matcha", 4.00, Tipo.BEBIDA, Map.of("Matcha", 1, "Leche", 1)),
        new Producto("Latte Nube", 5.00, Tipo.BEBIDA, Map.of("Cafe", 1, "Leche", 2)),
        new Producto("Horchata Latte", 5.50, Tipo.BEBIDA, Map.of("Cafe", 1, "Horchata", 2))
    );

    // ---------- Helpers ----------

    // Formats money with a dot and two decimals. Locale.US avoids a comma on Spanish computers.
    static String eur(double cantidad) {
        return String.format(Locale.US, "%.2f EUR", cantidad);
    }

    // Reads a menu option between min and max.
    // 'expirado' tells the method when to stop waiting (customer out of time).
    // It returns -1 on timeout, so the caller can react. Pass null for menus without a timer.
    // try/catch handles the user typing text instead of a number, and the loop asks again.
    static int leerOpcion(Entrada in, int min, int max, BooleanSupplier expirado) {
        while (true) {
            System.out.print("> ");
            String linea = in.leerLinea(expirado);
            if (linea == null) return -1;
            try {
                int n = Integer.parseInt(linea.trim());
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException e) {
                // Not a number: fall through and ask again.
            }
            System.out.println("Opcion no valida, intenta de nuevo.");
        }
    }

    // Picks a random product of one type. Filtering by type means the code does not
    // depend on the position of items in the menu list.
    static Producto aleatorioDeTipo(Tipo tipo) {
        List<Producto> candidatos = new ArrayList<>();
        for (Producto p : MENU) if (p.tipo == tipo) candidatos.add(p);
        return candidatos.get(RNG.nextInt(candidatos.size()));
    }

    // ---------- Game logic ----------

    // Same result in every timeout situation: customer leaves, reputation drops, no money.
    static double sePasoElTiempo(Cafeteria cafe) {
        System.out.println("\nSe acabo el tiempo. El cliente se marcha.");
        cafe.perderReputacion();
        return 0;
    }

    // Calculates what the customer pays when the order is delivered.
    // Rules are checked in priority order: lateness, then the dog rule, then the upgrade bonus.
    static double cobrar(Cliente c, Cafeteria cafe) {
        long segundos = c.segundosTranscurridos();
        if (c.llegoTarde()) {
            System.out.println("\nEntregado en " + segundos + " s. El cliente se fue molesto.");
            cafe.perderReputacion();
            return 0;
        }
        double pago = c.pedido.total();
        System.out.println("\nEntregado en " + segundos + " s. Pedido correcto!");
        if (c.tienePerro) {
            if (c.croquetaDada) {
                pago += 2.00;
                System.out.println("El perro esta feliz. Propina: +2.00 EUR");
            } else {
                // The punishment: half price and one reputation point lost.
                pago /= 2;
                cafe.perderReputacion();
                System.out.println("Olvidaste la croqueta: cobras la mitad.");
                System.out.println("El perro te mira con tristeza...");
            }
        }
        // The premium service tip only applies if the dog (if any) got its croqueta.
        if (cafe.propinasPremium && (!c.tienePerro || c.croquetaDada)) {
            pago += 1.00;
            System.out.println("Mejora de servicio: +1.00 EUR de propina.");
        }
        System.out.println("Cobras " + eur(pago));
        return pago;
    }

    // Handles one customer from arrival to delivery and returns the money earned.
    static double atender(Cliente c, Entrada in, Cafeteria cafe) {
        // The fast coffee machine upgrade adds 15 seconds to every customer's limit.
        int limite = c.urgencia.segundosLimite + (cafe.cafeteraRapida ? 15 : 0);
        System.out.println("\n------------------------------------------");
        System.out.println("Llega un cliente" + (c.tienePerro ? "... con un PERRO!" : "..."));
        System.out.println("\"" + c.urgencia.frase + "\"");
        System.out.println("Quiere: " + Pedido.texto(c.pedido.pendientes()));
        System.out.println("Tiempo limite: " + limite + " s");
        if (c.tienePerro) System.out.println("REGLA: croqueta gratis para el perro!");
        System.out.println("------------------------------------------");

        // The clock starts here. The Cronometro thread only prints warnings;
        // the real time check always uses the customer's own clock.
        c.iniciarReloj(limite);
        Cronometro reloj = new Cronometro(c, limite);
        reloj.start();
        try {
            while (true) {
                if (c.llegoTarde()) return sePasoElTiempo(cafe);

                System.out.println("\n1) Preparar producto");
                if (c.tienePerro) System.out.println("2) Dar croqueta al perro");
                System.out.println("3) Entregar pedido");
                System.out.println("4) Dejar ir al cliente");
                System.out.println("5) Ver inventario");

                // c::llegoTarde is passed as the timeout condition, so the game
                // stops waiting for input the moment the customer runs out of time.
                int opcion = leerOpcion(in, 1, 5, c::llegoTarde);
                if (opcion == -1) return sePasoElTiempo(cafe);

                switch (opcion) {
                    case 1 -> {
                        for (int i = 0; i < MENU.size(); i++)
                            System.out.println((i + 1) + ") " + MENU.get(i));
                        int eleccion = leerOpcion(in, 1, MENU.size(), c::llegoTarde);
                        if (eleccion == -1) return sePasoElTiempo(cafe);
                        Producto elegido = MENU.get(eleccion - 1);
                        // Check order: was it requested? Are there enough ingredients?
                        // Only then is the product marked as prepared.
                        if (!c.pedido.pendientes().contains(elegido)) {
                            System.out.println("Eso no lo pidio el cliente. Pierdes tiempo.");
                        } else if (!cafe.inventario.consumir(elegido.receta)) {
                            System.out.println("No tienes ingredientes suficientes para " + elegido.nombre);
                            System.out.println("Compra mas existencias en la tienda entre clientes.");
                        } else {
                            c.pedido.preparar(elegido);
                            System.out.println("Preparas: " + elegido.nombre);
                        }
                    }
                    case 2 -> {
                        if (c.tienePerro) {
                            c.croquetaDada = true;
                            System.out.println("Le das una croqueta al perro. Mueve la cola.");
                        } else System.out.println("Aqui no hay ningun perro.");
                    }
                    case 3 -> {
                        // Delivery is only allowed when nothing is left to prepare.
                        if (c.pedido.estaCompleto()) return cobrar(c, cafe);
                        System.out.println("Todavia falta: " + Pedido.texto(c.pedido.pendientes()));
                    }
                    case 4 -> {
                        System.out.println("El cliente se va sin su pedido.");
                        cafe.perderReputacion();
                        return 0;
                    }
                    case 5 -> cafe.inventario.mostrar();
                    default -> { }
                }
                System.out.println("Tiempo: " + c.segundosTranscurridos() + " / " + limite + " s");
            }
        } finally {
            // Always stop the clock thread, even if we leave through a return.
            reloj.interrupt();
            try { reloj.join(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    // Shop between customers: buy ingredients, buy upgrades, check stock.
    // No timer here, so menus use null as the timeout condition.
    static void tienda(Entrada in, Cafeteria cafe) {
        while (true) {
            System.out.println("\n===== TIENDA ===== Caja: " + eur(cafe.caja));
            System.out.println("1) Comprar ingredientes");
            System.out.println("2) Comprar mejoras");
            System.out.println("3) Ver inventario");
            System.out.println("4) Continuar");
            int opcion = leerOpcion(in, 1, 4, null);
            if (opcion == 4) return;
            if (opcion == 3) { cafe.inventario.mostrar(); continue; }
            if (opcion == 1) {
                // keySet() gives the ingredient names in insertion order (LinkedHashMap),
                // so the numbers shown on screen always match the same ingredients.
                List<String> nombres = new ArrayList<>(cafe.inventario.stock.keySet());
                for (int i = 0; i < nombres.size(); i++) {
                    String nombre = nombres.get(i);
                    System.out.println((i + 1) + ") " + nombre + " +5 unidades - "
                        + eur(cafe.inventario.precios.get(nombre)));
                }
                System.out.println((nombres.size() + 1) + ") Volver");
                int eleccion = leerOpcion(in, 1, nombres.size() + 1, null);
                if (eleccion <= nombres.size()) {
                    String nombre = nombres.get(eleccion - 1);
                    double precio = cafe.inventario.precios.get(nombre);
                    if (cafe.caja < precio) System.out.println("No tienes suficiente dinero.");
                    else if (!cafe.inventario.comprar(nombre, 5)) System.out.println("Almacen lleno. Compra la mejora de almacen.");
                    else {
                        cafe.caja -= precio;
                        System.out.println("Compraste 5 unidades de " + nombre + ".");
                    }
                }
            } else {
                System.out.println("1) Cafetera rapida (+15 s de margen por cliente) - 12 EUR"
                    + (cafe.cafeteraRapida ? " [COMPRADA]" : ""));
                System.out.println("2) Almacen grande (capacidad 40 por ingrediente) - 10 EUR"
                    + (cafe.almacenGrande ? " [COMPRADA]" : ""));
                System.out.println("3) Servicio premium (+1 EUR por pedido exitoso) - 15 EUR"
                    + (cafe.propinasPremium ? " [COMPRADA]" : ""));
                System.out.println("4) Volver");
                int mejora = leerOpcion(in, 1, 4, null);
                if (mejora == 4) continue;
                double precio = mejora == 1 ? 12 : mejora == 2 ? 10 : 15;
                boolean comprada = mejora == 1 ? cafe.cafeteraRapida
                    : mejora == 2 ? cafe.almacenGrande : cafe.propinasPremium;
                if (comprada) System.out.println("Ya tienes esta mejora.");
                else if (cafe.caja < precio) System.out.println("No tienes suficiente dinero.");
                else {
                    cafe.caja -= precio;
                    if (mejora == 1) cafe.cafeteraRapida = true;
                    if (mejora == 2) { cafe.almacenGrande = true; cafe.inventario.capacidad = 40; }
                    if (mejora == 3) cafe.propinasPremium = true;
                    System.out.println("Mejora comprada!");
                }
            }
        }
    }

    public static void main(String[] args) {
        Entrada in = new Entrada();
        System.out.println("==========================================");
        System.out.println("               CAFE JAVA");
        System.out.println("==========================================");
        System.out.println("Administra tu cafeteria durante " + DIAS + " dias.");
        System.out.println("Si tu reputacion llega a 0, pierdes.");
        System.out.println("1) Nueva partida\n2) Salir");
        if (leerOpcion(in, 1, 2, null) == 2) return;

        Cafeteria cafe = new Cafeteria();
        for (int dia = 1; dia <= DIAS; dia++) {
            // Difficulty grows each day: a higher money goal and more customers.
            double meta = 10 + dia * 8;
            int clientes = 3 + dia;
            double gananciaDia = 0;
            System.out.println("\n========== DIA " + dia + " / " + DIAS + " ==========");
            System.out.println("Meta: " + eur(meta) + " | Clientes: " + clientes);
            System.out.println("Reputacion: " + cafe.barraReputacion());

            // The shop opens at the start of the day and once around the middle,
            // instead of between every customer, to keep the game flowing.
            tienda(in, cafe);
            for (int i = 0; i < clientes; i++) {
                System.out.println("\nCliente " + (i + 1) + " de " + clientes);
                Cliente c = Cliente.aleatorio();
                double pago = atender(c, in, cafe);
                gananciaDia += pago;
                cafe.caja += pago;
                if (cafe.reputacion <= 0) {
                    System.out.println("Tu reputacion llego a 0. GAME OVER.");
                    return;
                }
                if (i + 1 == clientes / 2) tienda(in, cafe);
            }
            System.out.println("\nDIA " + dia + " terminado");
            System.out.println("Ventas del dia: " + eur(gananciaDia) + " | Meta: " + eur(meta));
            System.out.println("Caja disponible: " + eur(cafe.caja));
            if (gananciaDia < meta) {
                System.out.println("No alcanzaste la meta. GAME OVER.");
                return;
            }
        }
        System.out.println("\nHAS COMPLETADO LOS " + DIAS + " DIAS! Tu cafe es un exito!");
    }
}

// An enum is a fixed set of options, so COMIDA and BEBIDA cannot be mistyped like strings.
enum Tipo { COMIDA, BEBIDA }

// Each urgency level carries its own dialogue line and time limit.
// Adding a new level only needs one new line here.
enum Urgencia {
    CON_PRISA("Tengo prisa!", 45),
    NORMAL("Buenas, cuando puedas.", 60),
    SIN_PRISA("Sin prisa, voy a tomarme mi tiempo.", 90);
    final String frase;
    final int segundosLimite;
    Urgencia(String frase, int segundosLimite) {
        this.frase = frase;
        this.segundosLimite = segundosLimite;
    }
    // values() returns all enum constants, so a random index picks a random level.
    static Urgencia aleatoria() {
        Urgencia[] valores = values();
        return valores[CafeJava.RNG.nextInt(valores.length)];
    }
}

// A menu product. Fields are final because a product never changes once created.
class Producto {
    final String nombre;
    final double precio;
    final Tipo tipo;
    final Map<String, Integer> receta;
    Producto(String nombre, double precio, Tipo tipo, Map<String, Integer> receta) {
        this.nombre = nombre;
        this.precio = precio;
        this.tipo = tipo;
        this.receta = receta;
    }
    @Override public String toString() {
        return nombre + " - " + CafeJava.eur(precio) + " | " + receta;
    }
}

// An order keeps two lists: what the customer asked for and what has been prepared.
// The difference between them is what is still pending.
class Pedido {
    private final List<Producto> pedidos = new ArrayList<>();
    private final List<Producto> preparados = new ArrayList<>();
    void agregar(Producto p) { pedidos.add(p); }

    // Collections.frequency counts repeats, so ordering the same product twice works too.
    boolean preparar(Producto p) {
        if (Collections.frequency(preparados, p) < Collections.frequency(pedidos, p)) {
            preparados.add(p);
            return true;
        }
        return false;
    }

    // Copy the ordered list and remove one item for each prepared one.
    List<Producto> pendientes() {
        List<Producto> resto = new ArrayList<>(pedidos);
        for (Producto p : preparados) resto.remove(p);
        return resto;
    }
    boolean estaCompleto() { return pendientes().isEmpty(); }
    double total() {
        double suma = 0;
        for (Producto p : pedidos) suma += p.precio;
        return suma;
    }
    static String texto(List<Producto> lista) {
        List<String> nombres = new ArrayList<>();
        for (Producto p : lista) nombres.add(p.nombre);
        return nombres.isEmpty() ? "(nada, todo listo)" : String.join(" + ", nombres);
    }
}

// A customer: urgency, order, dog and the moment the clock started.
class Cliente {
    final Urgencia urgencia;
    final boolean tienePerro;
    final Pedido pedido = new Pedido();
    boolean croquetaDada;
    private long horaLlegada;
    private int limiteActual;
    Cliente(Urgencia urgencia, boolean tienePerro) {
        this.urgencia = urgencia;
        this.tienePerro = tienePerro;
    }

    // Factory method: always a drink, a 70% chance of food and a 30% chance of a dog.
    static Cliente aleatorio() {
        Cliente c = new Cliente(Urgencia.aleatoria(), CafeJava.RNG.nextInt(100) < 30);
        c.pedido.agregar(CafeJava.aleatorioDeTipo(Tipo.BEBIDA));
        if (CafeJava.RNG.nextInt(100) < 70)
            c.pedido.agregar(CafeJava.aleatorioDeTipo(Tipo.COMIDA));
        return c;
    }

    // nanoTime is meant for measuring elapsed time (unlike currentTimeMillis,
    // it does not jump if the computer's clock changes).
    void iniciarReloj(int limite) {
        horaLlegada = System.nanoTime();
        limiteActual = limite;
    }
    long segundosTranscurridos() {
        return (System.nanoTime() - horaLlegada) / 1_000_000_000L;
    }
    boolean llegoTarde() {
        return System.nanoTime() - horaLlegada >= limiteActual * 1_000_000_000L;
    }
}

// Wraps keyboard input so a read can give up when time runs out.
// Scanner.nextLine() blocks forever, so this uses BufferedReader.ready(), which says
// whether a line is waiting, and checks the timeout condition every 50 ms.
class Entrada {
    private final BufferedReader lector = new BufferedReader(new InputStreamReader(System.in));

    // Returns the typed line, or null if 'expirado' became true before the user typed anything.
    String leerLinea(BooleanSupplier expirado) {
        try {
            if (expirado != null) {
                while (!lector.ready()) {
                    if (expirado.getAsBoolean()) return null;
                    Thread.sleep(50);
                }
            }
            String linea = lector.readLine();
            if (linea == null) { // input stream closed (for example Ctrl+D)
                System.out.println("\nEntrada cerrada. Hasta luego.");
                System.exit(0);
            }
            return linea;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

// Independent thread that prints a warning at 30, 15, 10 and 5 seconds left.
// It runs next to the menu without blocking it. It is a daemon thread, so it
// never keeps the program alive on its own.
class Cronometro extends Thread {
    private final Cliente cliente;
    private final int limite;
    Cronometro(Cliente cliente, int limite) {
        this.cliente = cliente;
        this.limite = limite;
        setDaemon(true);
    }
    @Override public void run() {
        long ultimoAviso = -1; // avoids printing the same warning twice
        try {
            while (!isInterrupted() && !cliente.llegoTarde()) {
                long restante = Math.max(0, limite - cliente.segundosTranscurridos());
                boolean esAviso = restante == 30 || restante == 15 || restante == 10 || restante == 5;
                if (esAviso && restante != ultimoAviso) {
                    System.out.print("\n[RELOJ] Quedan " + restante + " segundos\n> ");
                    ultimoAviso = restante;
                }
                Thread.sleep(250); // short sleep so no second is skipped
            }
        } catch (InterruptedException e) {
            // The customer was served: just end the thread.
        }
    }
}

// Ingredient stock and prices. LinkedHashMap keeps insertion order,
// so ingredients always appear in the same order on screen.
class Inventario {
    final Map<String, Integer> stock = new LinkedHashMap<>();
    final Map<String, Double> precios = new LinkedHashMap<>();
    int capacidad = 20;
    Inventario() {
        registrar("Cafe", 12, 4.00);
        registrar("Leche", 12, 3.00);
        registrar("Pasteles", 10, 5.00);
        registrar("Pan", 12, 3.00);
        registrar("Huevos", 10, 3.00);
        registrar("Miel", 8, 2.00);
        registrar("Matcha", 10, 5.00);
        registrar("Horchata", 10, 4.00);
    }
    void registrar(String nombre, int cantidad, double precioPaquete) {
        stock.put(nombre, cantidad);
        precios.put(nombre, precioPaquete);
    }

    // Two passes on purpose: first check that EVERY ingredient is available,
    // then subtract. That way a recipe never takes only part of its ingredients.
    // 'synchronized' keeps this safe if two threads ever touch the stock at once.
    synchronized boolean consumir(Map<String, Integer> receta) {
        for (Map.Entry<String, Integer> e : receta.entrySet())
            if (stock.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        for (Map.Entry<String, Integer> e : receta.entrySet())
            stock.put(e.getKey(), stock.get(e.getKey()) - e.getValue());
        return true;
    }
    // Refuses the purchase if it would go over the storage capacity.
    boolean comprar(String ingrediente, int unidades) {
        int actual = stock.getOrDefault(ingrediente, 0);
        if (actual + unidades > capacidad) return false;
        stock.put(ingrediente, actual + unidades);
        return true;
    }
    void mostrar() {
        System.out.println("\n--- INVENTARIO (capacidad " + capacidad + ") ---");
        for (Map.Entry<String, Integer> e : stock.entrySet())
            System.out.println(e.getKey() + ": " + e.getValue() + " unidades");
    }
}

// The state of the cafe: money, reputation, upgrades and stock.
class Cafeteria {
    double caja = 15.00; // Starting money so the player can buy ingredients on day 1.
    int reputacion = 5;
    boolean cafeteraRapida;
    boolean almacenGrande;
    boolean propinasPremium;
    final Inventario inventario = new Inventario();

    // Math.max keeps reputation from going below zero.
    void perderReputacion() { reputacion = Math.max(0, reputacion - 1); }

    // Draws something like [###--] 3/5 by repeating characters.
    String barraReputacion() {
        return "[" + "#".repeat(reputacion) + "-".repeat(5 - reputacion) + "] " + reputacion + "/5";
    }
}
