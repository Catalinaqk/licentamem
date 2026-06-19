package org.example.cartemem;

import jakarta.servlet.http.HttpServletResponse;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
public class BookController {

    private final Driver driver;
    private final BookGeneratorAgent bookAgent;

    public BookController(Driver driver, BookGeneratorAgent bookAgent) {
        this.driver = driver;
        this.bookAgent = bookAgent;
    }

    // ==========================================
    // RUTE PENTRU AFIȘAREA PAGINILOR HTML
    // ==========================================

    @GetMapping("/login")
    public String arataPaginaLogin() {
        return "login";
    }

    @GetMapping("/register")
    public String arataPaginaRegister() {
        return "register";
    }

    @GetMapping({"/", "/carti"})
    public String arataCartile(@RequestParam(value = "q", required = false) String query,
                               @RequestParam(value = "gen", required = false) String gen,
                               @RequestParam(value = "user", required = false) String username,
                               Model model) {

        if ((query != null && !query.isEmpty()) || (gen != null && !gen.isEmpty())) {
            return incarcaPagina(query, gen, model, "galerie");
        }

        List<Map<String, String>> listaCartiPersonalizate = new ArrayList<>();
        List<String> listaGenuri = List.of("Bestseller", "Science Fiction", "Fantasy", "Horror", "Thriller", "Mister", "Romance", "Istorie", "Psihologie", "Documentar");

        try (Session session = driver.session()) {
            if (username != null && !username.isEmpty()) {
                String queryPersonalizat =
                        "MATCH (u:Utilizator {username: $user}) " +
                                "OPTIONAL MATCH (u)-[:INTERESAT_DE]->(t:Tag)<-[:ARE_TAG]-(c1:Carte) " +
                                "OPTIONAL MATCH (u)-[:A_CITIT]->(citita:Carte)-[:ARE_TAG]->(:Tag)<-[:ARE_TAG]-(c2:Carte) " +
                                "WITH collect(c1) + collect(c2) AS toate " +
                                "UNWIND toate AS c " +
                                "MATCH (c)-[:SCRISA_DE]->(a:Autor) " +
                                "WHERE c.categoria IS NOT NULL " +
                                "RETURN DISTINCT c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS desc, a.nume AS autor " +
                                "LIMIT 12";

                var result = session.run(queryPersonalizat, Map.of("user", username));

                while(result.hasNext()) {
                    Record r = result.next();
                    Map<String, String> carte = new HashMap<>();
                    carte.put("titlu", r.get("titlu").asString());
                    carte.put("autor", r.get("autor").asString());
                    carte.put("categorie", r.get("categorie").asString());
                    carte.put("imagine", r.get("imagine").asString());
                    carte.put("descriere", r.get("desc").isNull() ? "..." : r.get("desc").asString());
                    listaCartiPersonalizate.add(carte);
                }
            }

            if (listaCartiPersonalizate.isEmpty()) {
                return incarcaPagina(null, null, model, "galerie");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return incarcaPagina(null, null, model, "galerie");
        }

        model.addAttribute("carti", listaCartiPersonalizate);
        model.addAttribute("genuri", listaGenuri);
        model.addAttribute("selectatGen", "");
        model.addAttribute("cautare", "");
        model.addAttribute("esteCautare", false);

        return "galerie";
    }

    // ==========================================
    // RUTE API (BACKEND)
    // ==========================================

    @GetMapping("/api/carti/coperta")
    public void servesteImagineaCoperta(
            @RequestParam String titlu,
            @RequestParam(required = false, defaultValue = "Necunoscut") String autor,
            HttpServletResponse response) throws IOException {

        String urlCoperta = bookAgent.gasesteSauCreeazaCoperta(titlu, autor);

        try {
            if (urlCoperta.contains("placehold.co")) {
                response.sendRedirect(urlCoperta);
                return;
            }

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(urlCoperta))
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            HttpResponse<byte[]> imgResponse = client.send(req, HttpResponse.BodyHandlers.ofByteArray());

            if (imgResponse.statusCode() == 200) {
                String contentType = imgResponse.headers()
                        .firstValue("Content-Type")
                        .orElse("image/jpeg");

                response.setContentType(contentType);
                response.setHeader("Cache-Control", "public, max-age=86400");
                response.getOutputStream().write(imgResponse.body());
            } else {
                response.sendRedirect("https://placehold.co/300x450/f7fafc/004c4c?text=" +
                        URLEncoder.encode(titlu.length() > 20 ? titlu.substring(0, 20) : titlu,
                                StandardCharsets.UTF_8));
            }

        } catch (Exception e) {
            response.sendRedirect("https://placehold.co/300x450/f7fafc/004c4c?text=Cover");
        }
    }

    @PostMapping("/api/agent/experti-smart")
    @ResponseBody
    public String cereSfatulExpertilorSmart(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        String mesaj = (String) payload.get("mesaj");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> istoric = (List<Map<String, String>>) payload.get("istoric");

        if (username == null || username.isEmpty()) {
            return "Este necesară autentificarea pentru procesarea solicitării.";
        }

        return bookAgent.genereazaRecomandareGraphRAG(username, mesaj, istoric);
    }

    @PostMapping("/api/utilizator/actualizeaza-profil")
    @ResponseBody
    public String actualizeazaProfil(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) payload.get("tags");
        @SuppressWarnings("unchecked")
        List<String> experts = (List<String>) payload.get("experts");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> books = (List<Map<String, String>>) payload.get("books");

        bookAgent.salveazaProfilComplet(username, tags, experts, books);
        return "Succes";
    }

    @PostMapping("/api/carti/salveaza-interese")
    @ResponseBody
    public String salveazaInterese(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        @SuppressWarnings("unchecked")
        List<String> interese = (List<String>) payload.get("interese");

        bookAgent.salveazaProfilComplet(username, interese, null, null);
        return "Succes";
    }

    @GetMapping("/api/utilizator/profil")
    @ResponseBody
    public Map<String, Object> incarcaProfil(@RequestParam String username) {
        return bookAgent.incarcaProfil(username);
    }

    @PostMapping("/api/utilizator/adauga-lectura")
    @ResponseBody
    public String adaugaLecturaProfil(@RequestBody Map<String, String> payload) {
        bookAgent.adaugaLectura(payload.get("username"), payload.get("titlu"), payload.get("autor"));
        return "{\"status\":\"succes\"}";
    }

    @PostMapping("/api/utilizator/sterge-lectura")
    @ResponseBody
    public String stergeLecturaProfil(@RequestBody Map<String, String> payload) {
        bookAgent.stergeLectura(payload.get("username"), payload.get("titlu"));
        return "{\"status\":\"succes\"}";
    }

    @PostMapping("/api/delete-book")
    @ResponseBody
    public String stergeCarte(@RequestBody Map<String, String> payload) {
        String titlu = payload.get("titlu");
        try (Session session = driver.session()) {
            session.run("MATCH (c:Carte {titlu: $titlu}) DETACH DELETE c",
                    Map.of("titlu", titlu));
            return "Sistem: Datele au fost eliminate cu succes din AuraDB.";
        } catch (Exception e) {
            return "Eroare: Procedura de eliminare a întâmpinat o problemă.";
        }
    }

    @GetMapping("/api/agent/traseu-salvat")
    @ResponseBody
    public String getTraseuSalvat(@RequestParam("subiect") String subiect, @RequestParam("username") String username) {
        try (Session session = driver.session()) {
            // Caută traseul strict pentru acest utilizator
            var res = session.run("MATCH (t:Traseu {subiect: $sub, username: $u}) RETURN t.json AS json", Map.of("sub", subiect, "u", username));
            if(res.hasNext()) {
                return res.next().get("json").asString();
            }
        } catch(Exception e) {}
        return "[]";
    }

    @PostMapping("/api/agent/traseu-nou")
    @ResponseBody
    public String triggerNoulTraseuAgent(@RequestBody Map<String, String> payload) {
        String subiect = payload.get("subiect");
        int nrEtape = Integer.parseInt(payload.get("etape"));
        boolean stieBaze = Boolean.parseBoolean(payload.getOrDefault("stieBaze", "false"));
        String username = payload.get("username");

        return bookAgent.genereazaTraseuStructurat(username, subiect, nrEtape, stieBaze);
    }

    @GetMapping("/api/trasee/salvate")
    @ResponseBody
    public List<String> getToateTraseeleSalvate(@RequestParam("username") String username) {
        List<String> trasee = new ArrayList<>();
        try (Session session = driver.session()) {
            // Aduce doar lista cu traseele utilizatorului curent
            var result = session.run("MATCH (t:Traseu {username: $u}) RETURN t.subiect AS domeniu ORDER BY t.subiect ASC", Map.of("u", username));
            while(result.hasNext()) {
                trasee.add(result.next().get("domeniu").asString());
            }
        } catch (Exception e) {}
        return trasee;
    }

    @PostMapping("/api/agent/rezumat")
    @ResponseBody
    public String genereazaRezumat(@RequestBody Map<String, String> payload) {
        return bookAgent.genereazaRezumat(payload.get("titlu"), payload.get("autor"));
    }

    @GetMapping("/api/carti/detalii-complete")
    @ResponseBody
    public String getDetaliiCompleteCarte(@RequestParam String titlu, @RequestParam String autor) {
        return bookAgent.obtineDetaliiCompleteCarte(titlu, autor);
    }

    @PostMapping("/api/carti/noutati-internet")
    @ResponseBody
    public String getNoutatiDePeInternet(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> interese = (List<String>) payload.get("interese");
        return bookAgent.cautaNoutatiInternet(interese);
    }

    @PostMapping("/api/carti/noutati-personalizate")
    @ResponseBody
    public ResponseEntity<String> noutatiPersonalizate(@RequestBody Map<String, String> payload) {
        String subiect = payload.get("subiect");
        String rezultat = bookAgent.cautaNoutatiDupaSubiect(subiect);
        return ResponseEntity.ok(rezultat);
    }

    @GetMapping("/api/carti/toate")
    @ResponseBody
    public List<Map<String, String>> getToateCartile() {
        List<Map<String, String>> listaCarti = new ArrayList<>();
        try (Session session = driver.session()) {
            String query = "MATCH (c:CarteLibrarie)-[:SCRISA_DE]->(a:Autor) " +
                    "OPTIONAL MATCH (c)-[:ARE_TAG]->(t:Tag) " +
                    "WITH c, a, collect(t.nume) AS taguri " +
                    "ORDER BY id(c) DESC " +
                    "RETURN c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS descriere, a.nume AS autor, taguri " +
                    "LIMIT 1000";

            var result = session.run(query);
            while (result.hasNext()) {
                var r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("categorie", r.get("categorie").asString());
                carte.put("imagine", r.get("imagine").asString());
                var desc = r.get("descriere");
                carte.put("descriere", (desc.isNull() || desc.asString().isEmpty()) ? "Date indisponibile" : desc.asString());

                List<String> tags = new ArrayList<>();
                r.get("taguri").values().forEach(v -> tags.add(v.asString()));
                carte.put("taguri", String.join(" ", tags));

                listaCarti.add(carte);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return listaCarti;
    }

    @GetMapping("/api/carti/filtre-unice")
    @ResponseBody
    public Map<String, List<String>> getFiltreUnice() {
        List<String> autori = new ArrayList<>();
        List<String> genuri = new ArrayList<>();
        try (Session session = driver.session()) {
            var resAutori = session.run("MATCH (a:Autor) RETURN DISTINCT a.nume AS nume ORDER BY a.nume");
            while(resAutori.hasNext()) autori.add(resAutori.next().get("nume").asString());

            var resGenuri = session.run("MATCH (c:Carte) WHERE c.categoria IS NOT NULL RETURN DISTINCT c.categoria AS nume ORDER BY c.categoria");
            while(resGenuri.hasNext()) genuri.add(resGenuri.next().get("nume").asString());
        }
        return Map.of("autori", autori, "genuri", genuri);
    }

    @GetMapping("/api/social/cauta-utilizatori")
    @ResponseBody
    public List<String> cautaUtilizatori(@RequestParam String q, @RequestParam String current) {
        return bookAgent.cautaUtilizatori(q, current);
    }

    @PostMapping("/api/social/trimite-cerere")
    @ResponseBody
    public String trimiteCerere(@RequestBody Map<String, String> payload) {
        bookAgent.trimiteCererePrietenie(payload.get("sender"), payload.get("receiver"));
        return "{\"status\":\"succes\"}";
    }

    @GetMapping("/api/social/cereri")
    @ResponseBody
    public List<String> getCereri(@RequestParam String username) {
        return bookAgent.getCereriPrietenie(username);
    }

    @PostMapping("/api/social/raspunde-cerere")
    @ResponseBody
    public String raspundeCerere(@RequestBody Map<String, String> payload) {
        boolean acceptat = Boolean.parseBoolean(payload.get("acceptat"));
        bookAgent.raspundeCerere(payload.get("sender"), payload.get("receiver"), acceptat);
        return "{\"status\":\"succes\"}";
    }

    @GetMapping("/api/social/prieteni")
    @ResponseBody
    public List<String> getPrieteni(@RequestParam String username) {
        return bookAgent.getPrieteni(username);
    }

    @PostMapping("/api/social/trimite-recomandare")
    @ResponseBody
    public String trimiteRecomandare(@RequestBody Map<String, String> payload) {
        bookAgent.trimiteRecomandare(payload.get("sender"), payload.get("receiver"), payload.get("titlu"), payload.get("mesaj"));
        return "{\"status\":\"succes\"}";
    }

    @GetMapping("/api/social/recomandari")
    @ResponseBody
    public List<Map<String, String>> getRecomandari(@RequestParam String username) {
        return bookAgent.getRecomandariPrimite(username);
    }

    @PostMapping("/api/social/sterge-recomandare")
    @ResponseBody
    public String stergeRecomandare(@RequestBody Map<String, String> payload) {
        bookAgent.stergeRecomandare(payload.get("id"));
        return "{\"status\":\"succes\"}";
    }

    // ==========================================
    // RUTE PENTRU INREGISTRARE SI LOGIN
    // ==========================================

    @PostMapping("/api/utilizator/register")
    @ResponseBody
    public Map<String, String> proceseazaInregistrare(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.getOrDefault("username", "Anonim");
        String email = (String) payload.getOrDefault("email", "");

        String password = "";
        if (payload.containsKey("password")) {
            password = (String) payload.get("password");
        } else if (payload.containsKey("parola")) {
            password = (String) payload.get("parola");
        }

        Map<String, String> response = new HashMap<>();

        try (Session session = driver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("user", username);
            params.put("email", email);
            params.put("pass", password);

            session.run("MERGE (u:Utilizator {username: $user}) SET u.email = $email, u.parola = $pass", params);

            response.put("status", "success");
            response.put("message", "Cont creat cu succes! Te poți autentifica.");
        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "Eroare la salvarea în baza de date AuraDB.");
        }

        return response;
    }

    @PostMapping("/api/utilizator/login")
    @ResponseBody
    public Map<String, String> proceseazaLogin(@RequestBody Map<String, String> payload) {
        String email = payload.getOrDefault("email", "");

        String password = "";
        if (payload.containsKey("password")) {
            password = payload.get("password");
        } else if (payload.containsKey("parola")) {
            password = payload.get("parola");
        }

        Map<String, String> response = new HashMap<>();

        try (Session session = driver.session()) {
            Map<String, Object> params = new HashMap<>();
            params.put("email", email);
            params.put("pass", password);

            var result = session.run("MATCH (u:Utilizator {email: $email, parola: $pass}) RETURN u.username AS username", params);

            if (result.hasNext()) {
                String username = result.next().get("username").asString();
                response.put("status", "success");
                response.put("username", username);
                response.put("message", "Autentificare reușită!");
            } else {
                response.put("status", "error");
                response.put("message", "Email sau parolă incorecte!");
            }
        } catch (Exception e) {
            e.printStackTrace();
            response.put("status", "error");
            response.put("message", "Eroare la conexiunea cu baza de date.");
        }

        return response;
    }

    // ==========================================================
    // PUNCTUL DE ACCES PENTRU SALVARE JSON DIN BROWSER CONSOLE
    // ==========================================================
    @PostMapping("/api/admin/import-json")
    @ResponseBody
    public String importJsonDb(@RequestBody List<Map<String, Object>> payload) {
        return bookAgent.importaJsonInBazaDeDate(payload);
    }

    private String incarcaPagina(String query, String gen, Model model, String templateName) {
        List<Map<String, String>> listaCarti = new ArrayList<>();
        List<String> listaGenuri = List.of("Bestseller", "Science Fiction", "Fantasy", "Horror", "Thriller", "Mister", "Romance", "Istorie", "Psihologie", "Documentar");

        try (Session session = driver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (c:Carte)-[:SCRISA_DE]->(a:Autor) WHERE c.categoria IS NOT NULL ");
            Map<String, Object> params = new HashMap<>();

            boolean esteCautare = false;

            if (query != null && !query.isEmpty()) {
                cypher.append("AND (toLower(c.titlu) CONTAINS toLower($q) OR toLower(a.nume) CONTAINS toLower($q)) ");
                params.put("q", query);
                esteCautare = true;
            }
            if (gen != null && !gen.isEmpty()) {
                cypher.append("AND toLower(c.categoria) CONTAINS toLower($gen) ");
                params.put("gen", gen);
                esteCautare = true;
            }

            if (esteCautare) {
                cypher.append("RETURN c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS desc, a.nume AS autor ORDER BY id(c) DESC LIMIT 50");
            } else {
                cypher.append("WITH c, a, rand() AS randomSort ORDER BY randomSort RETURN c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS desc, a.nume AS autor LIMIT 8");
            }

            var result = session.run(cypher.toString(), params);
            while (result.hasNext()) {
                Record r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("categorie", r.get("categorie").asString());
                carte.put("imagine", r.get("imagine").asString());

                Value desc = r.get("desc");
                carte.put("descriere", (desc.isNull() || desc.asString().isEmpty()) ? "..." : desc.asString());

                listaCarti.add(carte);
            }

            model.addAttribute("esteCautare", esteCautare);
        }

        model.addAttribute("carti", listaCarti);
        model.addAttribute("genuri", listaGenuri);
        model.addAttribute("selectatGen", gen);
        model.addAttribute("cautare", query);
        return templateName;
    }
}