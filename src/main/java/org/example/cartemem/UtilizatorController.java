package org.example.cartemem;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
public class UtilizatorController {

    private final Driver driver;

    public UtilizatorController(Driver driver) {
        this.driver = driver;
    }

    // --- PAGINI HTML ---
    @GetMapping("/profil")
    public String paginaProfil() { return "profil"; }

    @GetMapping("/login")
    public String paginaLogin() { return "login"; }

    @GetMapping("/register")
    public String paginaRegister() { return "register"; }


    // --- API: ÎNREGISTRARE CONT NOU ---
    @PostMapping("/api/utilizator/register")
    @ResponseBody
    public Map<String, String> inregistrare(@RequestBody Map<String, String> payload) {
        String nume = payload.get("nume");
        String email = payload.get("email");
        String parola = payload.get("parola");

        if (nume == null || email == null || parola == null) {
            return Map.of("status", "eroare", "mesaj", "Date incomplete!");
        }

        try (Session session = driver.session()) {
            var check = session.run("MATCH (u:Utilizator {email: $email}) RETURN u", Map.of("email", email));
            if (check.hasNext()) {
                return Map.of("status", "eroare", "mesaj", "Acest email este deja folosit!");
            }

            session.run("CREATE (u:Utilizator {username: $nume, email: $email, parola: $parola})",
                    Map.of("nume", nume, "email", email, "parola", parola));

            return Map.of("status", "succes", "nume", nume);
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("status", "eroare", "mesaj", "Eroare la baza de date.");
        }
    }

    // --- API: LOGARE CONT EXISTENT ---
    @PostMapping("/api/utilizator/login")
    @ResponseBody
    public Map<String, String> logare(@RequestBody Map<String, String> payload) {
        String email = payload.get("email");
        String parola = payload.get("parola");

        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator {email: $email, parola: $parola}) RETURN u.username AS nume",
                    Map.of("email", email, "parola", parola));

            if (res.hasNext()) {
                String numeGasit = res.next().get("nume").asString();
                return Map.of("status", "succes", "nume", numeGasit);
            } else {
                return Map.of("status", "eroare", "mesaj", "Email sau parolă incorecte!");
            }
        } catch (Exception e) {
            return Map.of("status", "eroare", "mesaj", "Eroare la baza de date.");
        }
    }

    // --- API: ADUCE INTERESELE SALVATE ---
    @GetMapping("/api/utilizator/interese")
    @ResponseBody
    public List<String> getInterese(@RequestParam("username") String username) {
        List<String> interese = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator {username: $user})-[:INTERESAT_DE]->(t:Tag) RETURN t.nume AS nume",
                    Map.of("user", username));
            while(res.hasNext()) {
                String numeTag = res.next().get("nume").asString();
                String formatat = numeTag.substring(0, 1).toUpperCase() + numeTag.substring(1).toLowerCase();
                interese.add(formatat);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return interese;
    }

    // --- API: SALVARE INTERESE PROFIL ---
    @PostMapping("/api/utilizator/salveaza-interese")
    @ResponseBody
    public Map<String, String> salveazaInterese(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        List<String> interese = (List<String>) payload.get("interese");

        if (username == null || interese == null) {
            return Map.of("status", "eroare", "mesaj", "Date incomplete");
        }

        try (Session session = driver.session()) {
            session.run("MATCH (u:Utilizator {username: $user})-[r:INTERESAT_DE]->() DELETE r", Map.of("user", username));

            for (String interes : interese) {
                String query = "MATCH (u:Utilizator {username: $user}) " +
                        "MERGE (t:Tag {nume: $interes}) " +
                        "MERGE (u)-[:INTERESAT_DE]->(t)";
                session.run(query, Map.of("user", username, "interes", interes.toLowerCase().trim()));
            }
            return Map.of("status", "succes");
        } catch (Exception e) {
            e.printStackTrace();
            return Map.of("status", "eroare", "mesaj", "Eroare la baza de date");
        }
    }

    // --- NOU: API PENTRU ADĂUGARE CARTE CITITĂ ---
    @PostMapping("/api/utilizator/adauga-lectura")
    @ResponseBody
    public Map<String, String> adaugaLectura(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String titlu = payload.get("titlu");
        String autorNume = payload.get("autor");

        try (Session session = driver.session()) {
            // 1. Verificăm dacă nodul Carte există deja (după titlu)
            var result = session.run("MATCH (c:Carte {titlu: $titlu}) RETURN c", Map.of("titlu", titlu));

            if (!result.hasNext()) {
                // 2. DACĂ NU EXISTĂ, chemăm AI-ul/Google să o creeze cu toate detaliile
                // Notă: Poți injecta BookGeneratorAgent aici sau să faci o căutare simplă Google
                session.run("MERGE (a:Autor {nume: $autor}) " +
                                "MERGE (c:Carte {titlu: $titlu}) " +
                                "ON CREATE SET c.imagine = 'https://placehold.co/400x600?text=' + $titlu, c.categoria = 'Lectura' " +
                                "MERGE (c)-[:SCRISA_DE]->(a)",
                        Map.of("titlu", titlu, "autor", autorNume));
            }

            // 3. Facem legătura între Utilizator și Carte
            session.run("MATCH (u:Utilizator {username: $user}), (c:Carte {titlu: $titlu}) " +
                            "MERGE (u)-[:A_CITIT]->(c)",
                    Map.of("user", username, "titlu", titlu));

            return Map.of("status", "succes");
        } catch (Exception e) {
            return Map.of("status", "eroare");
        }
    }


    @PostMapping("/api/utilizator/sterge-lectura")
    @ResponseBody
    public Map<String, String> stergeLectura(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        String titlu = payload.get("titlu");

        try (Session session = driver.session()) {
            // Ștergem doar RELAȚIA :A_CITIT, nu și nodul Carte
            // (pentru că acea carte poate fi în biblioteca generală sau citită de alții)
            session.run("MATCH (u:Utilizator {username: $user})-[r:A_CITIT]->(c:Carte {titlu: $titlu}) " +
                            "DELETE r",
                    Map.of("user", username, "titlu", titlu));
            return Map.of("status", "succes");
        } catch (Exception e) {
            return Map.of("status", "eroare", "mesaj", e.getMessage());
        }
    }

    // --- NOU: API PENTRU OBȚINERE LISTĂ CĂRȚI CITITE ---
    @GetMapping("/api/utilizator/carti-citite")
    @ResponseBody
    public List<String> getCartiCitite(@RequestParam("username") String username) {
        List<String> carti = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator {username: $user})-[:A_CITIT]->(c:Carte) RETURN c.titlu AS titlu",
                    Map.of("user", username));
            while(res.hasNext()) {
                carti.add(res.next().get("titlu").asString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return carti;
    }
}