package org.example.cartemem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
public class BookGeneratorAgent {

    private final Driver driver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(60)).build();

    private final String ANTHROPIC_API_KEY = "";
    private final String MODEL_NAME = "claude-opus-4-7";
    private final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    public BookGeneratorAgent(Driver driver) {
        this.driver = driver;
    }

    private String trimitePromptLaClaude(String prompt) {
        try {
            Map<String, Object> message = Map.of("role", "user", "content", prompt);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", MODEL_NAME);
            requestBody.put("max_tokens", 4000);
            requestBody.put("messages", List.of(message));

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ANTHROPIC_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", ANTHROPIC_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                System.err.println("Eroare API Anthropic (" + resp.statusCode() + "): " + resp.body());
                return "Eroare API Anthropic (" + resp.statusCode() + ")";
            }

            JsonNode root = objectMapper.readTree(resp.body());
            return root.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare internă la comunicarea cu API-ul extern: " + e.getMessage();
        }
    }

    public String genereazaRezumat(String titlu, String autor) {
        try {
            String prompt = "Scrie un rezumat detaliat (plot summary) și o analiză a temelor pentru cartea '" + titlu + "' de " + autor + ". " +
                    "Textul trebuie să fie în limba română, minim 200 de cuvinte, formatat frumos în paragrafe. Fără Markdown de tip cod.";

            String text = trimitePromptLaClaude(prompt);

            if (!text.startsWith("Eroare")) {
                try (Session session = driver.session()) {
                    session.run("MATCH (c:Carte {titlu: $t}) SET c.descriere_ampla = $d", Map.of("t", titlu, "d", text));
                }
            }
            return text;
        } catch (Exception e) {
            return "Eroare internă la generarea rezumatului.";
        }
    }

    public String genereazaRecomandareGraphRAG(String username, String mesajUtilizator, List<Map<String, String>> istoric) {
        StringBuilder dateGraf = new StringBuilder();

        try (Session session = driver.session()) {
            var profilResult = session.run(
                    "MATCH (u:Utilizator {username: $user}) " +
                            "OPTIONAL MATCH (u)-[:INTERESAT_DE]->(t:Tag) " +
                            "OPTIONAL MATCH (u)-[:A_CITIT]->(c:Carte) " +
                            "RETURN collect(DISTINCT t.nume) AS interese, collect(DISTINCT c.titlu) AS citite",
                    Map.of("user", username)
            );

            if (profilResult.hasNext()) {
                var rand = profilResult.next();
                List<Object> interese = rand.get("interese").asList();
                List<Object> citite = rand.get("citite").asList();

                dateGraf.append("PROFILUL UTILIZATORULUI:\n");
                dateGraf.append("- Domenii de interes: ").append(interese.isEmpty() ? "Nespecificat" : interese.toString()).append("\n");
                dateGraf.append("- Cărți deja citite: ").append(citite.isEmpty() ? "Niciuna salvată" : citite.toString()).append("\n\n");
            }

            var recomandariPersonalizate = session.run(
                    "MATCH (u:Utilizator {username: $user}) " +
                            "OPTIONAL MATCH (u)-[:INTERESAT_DE]->(t:Tag)<-[:ARE_TAG]-(c1:Carte) " +
                            "OPTIONAL MATCH (u)-[:A_CITIT]->(:Carte)-[:ARE_TAG]->(t2:Tag)<-[:ARE_TAG]-(c2:Carte) " +
                            "WITH u, collect(c1) + collect(c2) AS toate, collect(t.nume) + collect(t2.nume) as taguriInteres " +
                            "UNWIND toate AS c " +
                            "MATCH (c)-[:SCRISA_DE]->(a:Autor) " +
                            "WHERE c IS NOT NULL AND NOT EXISTS((u)-[:A_CITIT]->(c)) " +
                            "WITH DISTINCT c, a, taguriInteres " +
                            "OPTIONAL MATCH (c)-[:ARE_TAG]->(t:Tag) " +
                            "WITH c, a, count(t) AS scor, collect(t.nume) AS motive " +
                            "ORDER BY scor DESC " +
                            "RETURN c.titlu AS titlu, a.nume AS autor, scor, motive LIMIT 4",
                    Map.of("user", username)
            );

            dateGraf.append("RECOMANDĂRI POSIBILE DIN AURADB:\n");
            while (recomandariPersonalizate.hasNext()) {
                var r = recomandariPersonalizate.next();
                dateGraf.append("- '").append(r.get("titlu").asString())
                        .append("' de ").append(r.get("autor").asString()).append("\n");
            }
        } catch (Exception e) {}

        StringBuilder historyStr = new StringBuilder();
        if (istoric != null && !istoric.isEmpty()) {
            historyStr.append("ISTORICUL CONVERSAȚIEI:\n");
            int start = Math.max(0, istoric.size() - 6);
            for (int i = start; i < istoric.size() - 1; i++) {
                Map<String, String> msg = istoric.get(i);
                String sender = msg.get("sender").equals("user") ? "Utilizatorul" : "Sistemul (AI)";
                historyStr.append(sender).append(": ").append(msg.get("text")).append("\n");
            }
        }

        String promptPentruClaude =
                "Ești Mentorul Literar personal al utilizatorului '" + username + "'. Ești un asistent conversațional, profesionist și orientat spre studiu.\n\n" +
                        "ISTORICUL CONVERSAȚIEI:\n" + historyStr.toString() + "\n\n" +
                        "DATE EXTRASE DIN BAZA DE DATE:\n" + dateGraf.toString() + "\n\n" +
                        "MESAJUL NOU DE LA UTILIZATOR: \"" + mesajUtilizator + "\"\n\n" +
                        "REGULI CRITICE:\n" +
                        "1. MEMORIE: NU recomanda NICIODATĂ o carte deja menționată în istoric.\n" +
                        "2. STIL: Poartă un dialog natural.\n" +
                        "3. FORMAT OBLIGATORIU: Ori de câte ori recomanzi o carte specifică, ești OBLIGAT ABSOLUT să adaugi la sfârșitul mesajului acest format exact: [CARTE: Titlul Cărții | Nume Autor]. Exemplu: [CARTE: Dune | Frank Herbert].";

        return trimitePromptLaClaude(promptPentruClaude);
    }

    public String genereazaTraseuStructurat(String username, String subiect, int nrEtape, boolean stieBaze) {
        try {
            String nivelInstructiune = stieBaze ?
                    "Utilizatorul a confirmat cunoștințele de bază. Începe cu nivelul Intermediar și progresează spre Avansat." :
                    "Utilizatorul nu deține cunoștințe prealabile. Începe cu fundamentele teoretice.";

            StringBuilder contextBazaDeDate = new StringBuilder();
            try (Session session = driver.session()) {
                String query = "MATCH (c:Carte) OPTIONAL MATCH (c)-[:ARE_TAG]->(t:Tag) " +
                        "WHERE toLower(c.categoria) CONTAINS toLower($subiect) OR toLower(c.titlu) CONTAINS toLower($subiect) OR toLower(t.nume) CONTAINS toLower($subiect) " +
                        "WITH DISTINCT c LIMIT 20 RETURN c.titlu AS titlu, c.autor AS autor, c.descriere AS descriere";
                var result = session.run(query, Map.of("subiect", subiect));
                while (result.hasNext()) {
                    var r = result.next();
                    contextBazaDeDate.append("- '").append(r.get("titlu").asString()).append("' de ").append(r.get("autor").asString()).append("\n");
                }
            }

            StringBuilder cartiCitite = new StringBuilder();
            if (username != null && !username.isEmpty()) {
                try (Session session = driver.session()) {
                    var res = session.run("MATCH (u:Utilizator {username: $u})-[:A_CITIT]->(c:Carte) RETURN c.titlu AS titlu", Map.of("u", username));
                    while(res.hasNext()) {
                        cartiCitite.append("- ").append(res.next().get("titlu").asString()).append("\n");
                    }
                }
            }

            String regulaCartiCitite = cartiCitite.length() > 0 ?
                    "REGULĂ: Utilizatorul a citit deja următoarele cărți. ESTE INTERZIS să le incluzi în acest traseu:\n" + cartiCitite.toString() + "\n" : "";

            // MODIFICAREA ESTE AICI: I-am impus reguli extrem de stricte pentru cărțile selectate.
            String prompt = "Elaborează un plan de studiu structurat pentru subiectul: '" + subiect + "' în exact " + nrEtape + " etape logice. \n" +
                    nivelInstructiune + "\n\n" +
                    regulaCartiCitite +
                    "Referințe din baza de date (folosește-le DOAR dacă se potrivesc perfect): \n" + contextBazaDeDate.toString() + "\n\n" +
                    "REGULĂ CRITICĂ: Trebuie să recomanzi DOAR CĂRȚI REALE, recunoscute la nivel internațional, strict legate de subiectul cerut (" + subiect + "). " +
                    "Dacă lista mea de referințe este goală, caută în baza ta de date generală manuale, tratate sau cărți de specialitate relevante. " +
                    "ESTE STRICT INTERZIS să folosești cărți de ficțiune, romane sau exemple generice (cum ar fi Jane Austen) pentru domenii tehnice, științifice sau legislative!\n\n" +
                    "Răspunde STRICT utilizând un Array JSON valid, fără explicații. Structura cerută: " +
                    "[ { \"nivel\": \"INTRODUCERE\", \"titlu_etapa\": \"...\", \"descriere\": \"...\", \"carti\": [ { \"titlu\": \"...\", \"autor\": \"...\", \"an\": 2024, \"descriere\": \"...\" } ] } ]";

            String raspunsClaude = trimitePromptLaClaude(prompt);
            String curatat = raspunsClaude.replace("```json", "").replace("```", "").trim();
            int start = curatat.indexOf("[");
            int end = curatat.lastIndexOf("]");

            if (start != -1 && end != -1) {
                String finalJson = curatat.substring(start, end + 1);
                try (Session session = driver.session()) {
                    session.run("MERGE (tr:Traseu {subiect: $sub, username: $u}) SET tr.json = $json",
                            Map.of("sub", subiect, "u", username, "json", finalJson));
                }
                return finalJson;
            }
            return "[{\"titlu_etapa\": \"Eroare\", \"descriere\": \"Structura JSON invalidă.\"}]";
        } catch (Exception e) {
            return "[{\"titlu_etapa\": \"Eroare\", \"descriere\": \"Problemă tehnică.\"}]";
        }
    }

    public String cautaNoutatiInternet(List<String> interese) {
        String intereseUser = (interese == null || interese.isEmpty()) ? "Lucrări generale" : String.join(", ", interese);
        String dataCurenta = java.time.LocalDate.now().toString();

        String prompt = "Astăzi este data de " + dataCurenta + ". Acționează ca un curator de publicații recente. Extrage lansări REALE de cărți din ultima lună. " +
                "Returnează EXACT un Array JSON cu 4 categorii distincte. A patra categorie se va numi 'Recomandări pentru tine' bazată pe: " + intereseUser + ". " +
                "Format cerut: [{\"gen\": \"Nume Gen\", \"carti\": [{\"titlu\": \"Titlu Real\", \"autor\": \"Autor Real\", \"imagine\": \"\", \"descriere\": \"...\"}]}]";

        String raspunsClaude = trimitePromptLaClaude(prompt);
        try {
            String curatat = raspunsClaude.replace("```json", "").replace("```", "").trim();
            int start = curatat.indexOf("[");
            int end = curatat.lastIndexOf("]");
            if (start != -1 && end != -1) return curatat.substring(start, end + 1);
        } catch (Exception e) { }
        return "[]";
    }


    public String obtineDetaliiCompleteCarte(String titlu, String autor) {
        try {
            try (Session session = driver.session()) {
                String checkQuery = "MATCH (c:Carte {titlu: $t}) RETURN c.descriere_ampla AS desc, c.despre_autor AS autor_info, c.teme_principale AS teme";
                var result = session.run(checkQuery, Map.of("t", titlu));
                if (result.hasNext()) {
                    var r = result.next();
                    if (r.get("desc") != null && !r.get("desc").isNull()) {
                        Map<String, Object> raspunsDB = new HashMap<>();
                        raspunsDB.put("sursa_date", "AURADB");
                        raspunsDB.put("rezumat", r.get("desc").asString());
                        raspunsDB.put("despre_autor", r.get("autor_info") != null ? r.get("autor_info").asString() : "Informații indisponibile.");
                        raspunsDB.put("teme_principale", r.get("teme") != null ? r.get("teme").asList() : new ArrayList<>());
                        raspunsDB.put("link_google", "https://www.google.com/search?tbm=bks&q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                        raspunsDB.put("link_amazon", "https://www.amazon.com/s?k=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                        raspunsDB.put("link_carturesti", "https://carturesti.ro/cautare?q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                        return objectMapper.writeValueAsString(raspunsDB);
                    }
                }
            }

            String prompt = "Redactează o analiză aprofundată pentru '" + titlu + "' de " + autor + ". " +
                    "Returnează EXACT JSON: {\"rezumat\": \"minim 150 cuvinte\", \"despre_autor\": \"...\", \"teme_principale\": [\"Tema 1\", \"Tema 2\"]}";

            String raspunsClaude = trimitePromptLaClaude(prompt);
            int start = raspunsClaude.indexOf("{");
            int end = raspunsClaude.lastIndexOf("}");

            if (start != -1 && end != -1) {
                String jsonClaude = raspunsClaude.substring(start, end + 1);
                JsonNode dateAI = objectMapper.readTree(jsonClaude);

                String rezumat = dateAI.path("rezumat").asText();
                String despreAutor = dateAI.path("despre_autor").asText();
                List<String> teme = new ArrayList<>();
                dateAI.path("teme_principale").forEach(node -> teme.add(node.asText()));

                try (Session session = driver.session()) {
                    session.run("MERGE (c:Carte {titlu: $t}) SET c.autor = $a, c.descriere_ampla = $desc, c.despre_autor = $autor_info, c.teme_principale = $teme",
                            Map.of("t", titlu, "a", autor, "desc", rezumat, "autor_info", despreAutor, "teme", teme));
                }

                Map<String, Object> raspunsFinal = new HashMap<>();
                raspunsFinal.put("sursa_date", "GENERAT DE AI");
                raspunsFinal.put("rezumat", rezumat);
                raspunsFinal.put("despre_autor", despreAutor);
                raspunsFinal.put("teme_principale", teme);
                raspunsFinal.put("link_google", "https://www.google.com/search?tbm=bks&q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                raspunsFinal.put("link_amazon", "https://www.amazon.com/s?k=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                raspunsFinal.put("link_carturesti", "https://carturesti.ro/cautare?q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));

                return objectMapper.writeValueAsString(raspunsFinal);
            }
            return "{\"eroare\": \"Eroare de formatare JSON.\"}";
        } catch (Exception e) {
            return "{\"eroare\": \"Problemă tehnică.\"}";
        }
    }

    private String getBookCoverUrl(String titlu, String autor) {
        try {
            String query = URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8);
            String url = "https://openlibrary.org/search.json?q=" + query + "&limit=1&fields=cover_i,title";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(r.body());
                JsonNode docs = root.path("docs");
                if (docs.isArray() && docs.size() > 0 && !docs.get(0).path("cover_i").isMissingNode()) {
                    return "https://covers.openlibrary.org/b/id/" + docs.get(0).path("cover_i").asText() + "-L.jpg";
                }
            }
        } catch (Exception e) {}

        try {
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8) + "&maxResults=3";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "Mozilla/5.0").GET().build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode n = objectMapper.readTree(r.body());
                if (n.has("items")) {
                    for (JsonNode item : n.get("items")) {
                        JsonNode links = item.path("volumeInfo").path("imageLinks");
                        if (!links.isMissingNode() && links.has("thumbnail")) {
                            return links.path("thumbnail").asText().replace("http://", "https://").replace("&edge=curl", "").replace("zoom=1", "zoom=0");
                        }
                    }
                }
            }
        } catch (Exception e) {}

        return "https://placehold.co/300x450/f7fafc/004c4c?text=" + URLEncoder.encode(titlu.length() > 25 ? titlu.substring(0, 25) + "..." : titlu, StandardCharsets.UTF_8);
    }

    public String gasesteSauCreeazaCoperta(String titlu, String autor) {
        try (Session session = driver.session()) {
            var res = session.run("MATCH (c:Carte {titlu: $t}) RETURN c.imagine AS img", Map.of("t", titlu));
            if (res.hasNext()) {
                var val = res.next().get("img");
                if (!val.isNull()) {
                    String dbImg = val.asString();
                    if (dbImg != null && !dbImg.contains("placehold.co") && !dbImg.equals("null") && !dbImg.isEmpty()) {
                        return dbImg;
                    }
                }
            }
        } catch (Exception e) {}

        String imgUrl = getBookCoverUrl(titlu, autor);
        try (Session session = driver.session()) {
            session.run("MERGE (c:Carte {titlu: $t}) SET c.imagine = $img", Map.of("t", titlu, "img", imgUrl));
        } catch (Exception e) {}
        return imgUrl;
    }

    public void salveazaProfilComplet(String username, List<String> tags, List<String> experts, List<Map<String, String>> books) {
        try (Session session = driver.session()) {
            session.run("MERGE (u:Utilizator {username: $u})", Map.of("u", username));
            session.run("MATCH (u:Utilizator {username: $u})-[r:INTERESAT_DE]->() DELETE r", Map.of("u", username));

            if (tags != null) {
                for (String t : tags) {
                    if (t == null || t.trim().isEmpty()) continue;
                    session.run("MATCH (u:Utilizator {username: $u}) MERGE (tag:Tag {nume: $t}) MERGE (u)-[r:INTERESAT_DE]->(tag) SET r.score = 5.0", Map.of("u", username, "t", t.trim()));
                }
            }

            if (books != null) {
                for (Map<String, String> b : books) {
                    adaugaLectura(username, b.get("title"), b.get("author"));
                }
            }
        } catch (Exception e) {}
    }

    public Map<String, Object> incarcaProfil(String username) {
        Map<String, Object> rezultat = new HashMap<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator {username: $u}) OPTIONAL MATCH (u)-[:INTERESAT_DE]->(t:Tag) WITH u, collect(DISTINCT t.nume) as tags OPTIONAL MATCH (u)-[:A_CITIT]->(c:Carte) RETURN tags, collect(DISTINCT {title: c.titlu, author: c.autor}) as books", Map.of("u", username));
            if (res.hasNext()) {
                var r = res.next();
                rezultat.put("tags", r.get("tags").asList());
                rezultat.put("experts", new ArrayList<>());
                rezultat.put("books", r.get("books").asList());
            }
        } catch (Exception e) {}
        return rezultat;
    }

    // AICI ESTE FIX-UL CARE SUPRASCRIE "null"
    public void adaugaLectura(String username, String titlu, String autor) {
        String imgUrl = gasesteSauCreeazaCoperta(titlu, autor);
        try (Session session = driver.session()) {
            String query = "MERGE (u:Utilizator {username: $u}) " +
                    "MERGE (c:Carte {titlu: $t}) " +
                    "ON CREATE SET c.autor = $a, c.imagine = $img " +
                    "MERGE (u)-[:A_CITIT]->(c)";
            session.run(query, Map.of("u", username, "t", titlu, "a", autor, "img", imgUrl));

            // Dacă autorul este introdus corect, curățăm din graf vechile "null"
            if (autor != null && !autor.isEmpty() && !autor.equalsIgnoreCase("Necunoscut") && !autor.equalsIgnoreCase("null")) {
                session.run("MATCH (c:Carte {titlu: $t}) WHERE c.autor IS NULL OR c.autor = 'null' OR c.autor = 'Necunoscut' SET c.autor = $a",
                        Map.of("t", titlu, "a", autor));
            }
        } catch (Exception e) {}
    }

    public void stergeLectura(String username, String titlu) {
        try (Session session = driver.session()) {
            session.run("MATCH (u:Utilizator {username: $u})-[r:A_CITIT]->(c:Carte {titlu: $t}) DELETE r", Map.of("u", username, "t", titlu));
        } catch (Exception e) {}
    }

    // ==========================================
    // MODUL SOCIAL
    // ==========================================

    public List<String> cautaUtilizatori(String query, String currentUser) {
        List<String> users = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator) WHERE toLower(u.username) CONTAINS toLower($q) AND u.username <> $cu RETURN u.username AS nume LIMIT 10", Map.of("q", query, "cu", currentUser));
            while(res.hasNext()) users.add(res.next().get("nume").asString());
        } catch (Exception e) {}
        return users;
    }

    public void trimiteCererePrietenie(String sender, String receiver) {
        try (Session session = driver.session()) {
            session.run("MATCH (u1:Utilizator {username: $s}), (u2:Utilizator {username: $r}) MERGE (u1)-[:CERERE_PRIETENIE]->(u2)", Map.of("s", sender, "r", receiver));
        } catch (Exception e) {}
    }

    public List<String> getCereriPrietenie(String username) {
        List<String> cereri = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (s:Utilizator)-[:CERERE_PRIETENIE]->(u:Utilizator {username: $u}) RETURN s.username AS nume", Map.of("u", username));
            while(res.hasNext()) cereri.add(res.next().get("nume").asString());
        } catch (Exception e) {}
        return cereri;
    }

    public void raspundeCerere(String sender, String receiver, boolean acceptat) {
        try (Session session = driver.session()) {
            session.run("MATCH (u1:Utilizator {username: $s})-[r:CERERE_PRIETENIE]->(u2:Utilizator {username: $r}) DELETE r", Map.of("s", sender, "r", receiver));
            if (acceptat) {
                session.run("MATCH (u1:Utilizator {username: $s}), (u2:Utilizator {username: $r}) MERGE (u1)-[:PRIETEN_CU]-(u2)", Map.of("s", sender, "r", receiver));
            }
        } catch (Exception e) {}
    }

    public List<String> getPrieteni(String username) {
        List<String> prieteni = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (u:Utilizator {username: $u})-[:PRIETEN_CU]-(p:Utilizator) RETURN DISTINCT p.username AS nume", Map.of("u", username));
            while(res.hasNext()) prieteni.add(res.next().get("nume").asString());
        } catch (Exception e) {}
        return prieteni;
    }

    public void trimiteRecomandare(String sender, String receiver, String titluCarte, String mesaj) {
        try (Session session = driver.session()) {
            String id = UUID.randomUUID().toString();
            session.run("MERGE (r:Recomandare {id: $id}) SET r.sender = $s, r.receiver = $rec, r.titlu = $t, r.mesaj = $m", Map.of("id", id, "s", sender, "rec", receiver, "t", titluCarte, "m", mesaj != null ? mesaj : ""));
        } catch (Exception e) {}
    }

    public List<Map<String, String>> getRecomandariPrimite(String username) {
        List<Map<String, String>> recs = new ArrayList<>();
        try (Session session = driver.session()) {
            var res = session.run("MATCH (r:Recomandare {receiver: $u}) RETURN r.id AS id, r.sender AS sender, r.titlu AS titlu, r.mesaj AS mesaj", Map.of("u", username));
            while(res.hasNext()) {
                var record = res.next();
                recs.add(Map.of("id", record.get("id").asString(), "sender", record.get("sender").asString(), "titlu", record.get("titlu").asString(), "mesaj", record.get("mesaj").asString()));
            }
        } catch (Exception e) {}
        return recs;
    }

    public void stergeRecomandare(String id) {
        try (Session session = driver.session()) {
            session.run("MATCH (r:Recomandare {id: $id}) DELETE r", Map.of("id", id));
        } catch (Exception e) {}
    }

    // ==========================================
    // IMPORT BAZĂ DE DATE (JSON -> AURADB)
    // ==========================================
    public String importaJsonInBazaDeDate(List<Map<String, Object>> carti) {
        int count = 0;
        try (Session session = driver.session()) {
            for (Map<String, Object> carte : carti) {
                String titlu = (String) carte.get("titlu");
                String autor = (String) carte.get("autor");
                String categorie = (String) carte.get("categorie");
                String imagine = (String) carte.get("imagine");
                String descriere = (String) carte.get("descriere");

                if (titlu == null || autor == null) continue;

                // Salvăm ca și :Carte:CarteLibrarie pentru a le separa de nodurile fantomă
                session.run("MERGE (c:Carte:CarteLibrarie {titlu: $t}) " +
                                "SET c.autor = $a, c.categoria = $cat, c.imagine = $img, c.descriere = $desc " +
                                "MERGE (au:Autor {nume: $a}) " +
                                "MERGE (c)-[:SCRISA_DE]->(au)",
                        Map.of("t", titlu, "a", autor,
                                "cat", categorie != null ? categorie : "General",
                                "img", imagine != null ? imagine : "https://placehold.co/300x450",
                                "desc", descriere != null ? descriere : "Fără descriere"));

                @SuppressWarnings("unchecked")
                List<String> tags = (List<String>) carte.get("cuvinte_cheie");
                if (tags != null) {
                    for (String tag : tags) {
                        session.run("MATCH (c:Carte {titlu: $t}) " +
                                        "MERGE (tg:Tag {nume: $tag}) " +
                                        "MERGE (c)-[:ARE_TAG]->(tg)",
                                Map.of("t", titlu, "tag", tag.toLowerCase().trim()));
                    }
                }
                count++;
            }
            return "Succes: " + count + " cărți adăugate în AuraDB.";
        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare import: " + e.getMessage();
        }
    }



    public void streamRezumatLaClaude(String titlu, String autor, SseEmitter emitter) {
        new Thread(() -> {
            try {
                String prompt = "Scrie un rezumat detaliat și o analiză a temelor pentru cartea '" + titlu + "' de " + autor + ". " +
                        "Textul trebuie să fie în limba română, minim 200 de cuvinte, formatat frumos în paragrafe. Fără Markdown de tip cod.";

                Map<String, Object> message = Map.of("role", "user", "content", prompt);
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("model", MODEL_NAME);
                requestBody.put("max_tokens", 4000);
                requestBody.put("stream", true); // SETARE CRUCIALĂ PENTRU STREAMING
                requestBody.put("messages", List.of(message));

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ANTHROPIC_URL))
                        .header("Content-Type", "application/json")
                        .header("x-api-key", ANTHROPIC_API_KEY)
                        .header("anthropic-version", "2023-06-01")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

                // Preia răspunsul ca InputStream (flux continuu de date)
                HttpResponse<InputStream> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofInputStream());

                BufferedReader reader = new BufferedReader(new InputStreamReader(resp.body(), StandardCharsets.UTF_8));
                String line;
                StringBuilder raspunsComplet = new StringBuilder();

                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if (data.equals("[DONE]")) continue;
                        try {
                            JsonNode node = objectMapper.readTree(data);
                            if (node.has("type") && node.get("type").asText().equals("content_block_delta")) {
                                String textChunk = node.path("delta").path("text").asText();
                                raspunsComplet.append(textChunk);

                                // Înlocuim newline-urile cu <br> pentru afișarea corectă în frontend
                                String safeChunk = textChunk.replace("\n", "<br>");
                                emitter.send(safeChunk); // Trimitem bucățica spre frontend
                            }
                        } catch (Exception ex) {
                            // Ignorăm erorile de parsare JSON pentru linii incomplete
                        }
                    }
                }

                // La final, salvăm răspunsul complet în Neo4j (Caching)
                try (Session session = driver.session()) {
                    session.run("MATCH (c:Carte {titlu: $t}) SET c.descriere_ampla = $d",
                            Map.of("t", titlu, "d", raspunsComplet.toString()));
                }

                // Închidem conexiunea cu succes
                emitter.complete();

            } catch (Exception e) {
                try {
                    emitter.send("Eroare la generarea textului.");
                    emitter.completeWithError(e);
                } catch(Exception ignored) {}
            }
        }).start();
    }






    public void importMasiv(List<Map<String, Object>> listaCarti) {
        try (Session session = driver.session()) {
            String cypher = "UNWIND $lista AS dateCarte " +
                    "MERGE (c:Carte {titlu: dateCarte.titlu}) " +
                    "SET c.an = dateCarte.an, c.editura = dateCarte.editura, c.limba = dateCarte.limba, c.categoria = dateCarte.categorie, c.nr_pagini = dateCarte.nr_pagini " +
                    "MERGE (a:Autor {nume: dateCarte.autor}) " +
                    "MERGE (c)-[:SCRISA_DE]->(a) " +
                    "WITH c, dateCarte " +
                    "UNWIND dateCarte.cuvinte_cheie AS cuvant " +
                    "MERGE (t:Tag {nume: trim(toLower(toString(cuvant)))}) " +
                    "MERGE (c)-[:ARE_TAG]->(t)";
            session.run(cypher, Map.of("lista", listaCarti));
        }
    }

    public String genereazaSiSalveaza(String gen) { return "Metodă dezactivată."; }
    public String genereazaPersonalizat(String sursa, String autor) { return "Metodă dezactivată."; }
    public String reparaDateLipsa() { return "Metodă dezactivată."; }
    public List<Map<String, String>> recomandaDupaTag(String input) { return new ArrayList<>(); }
}