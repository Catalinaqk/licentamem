package org.example.cartemem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.stereotype.Service;

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

    // ==========================================
    // SETĂRI ANTHROPIC (CLAUDE)
    // ==========================================
    private final String ANTHROPIC_API_KEY = ""; // <-- PUNE CHEIA AICI
    private final String MODEL_NAME = "claude-opus-4-7"; // Cel mai bun și inteligent model curent
    private final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";

    public BookGeneratorAgent(Driver driver) {
        this.driver = driver;
    }

    // --- METODA CENTRALĂ DE COMUNICARE CU CLAUDE ---
    private String trimitePromptLaClaude(String prompt) {
        try {
            Map<String, Object> message = Map.of("role", "user", "content", prompt);
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", MODEL_NAME);
            requestBody.put("max_tokens", 4000); // Claude are nevoie să știe limita maximă de cuvinte
            requestBody.put("messages", List.of(message));

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(ANTHROPIC_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", ANTHROPIC_API_KEY)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                System.err.println("❌ Eroare API Anthropic (" + resp.statusCode() + "): " + resp.body());
                return "Eroare API Anthropic (" + resp.statusCode() + ")";
            }

            JsonNode root = objectMapper.readTree(resp.body());
            return root.path("content").get(0).path("text").asText();

        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare internă la comunicarea cu Claude: " + e.getMessage();
        }
    }

    // --- METODĂ AJUTĂTOARE PENTRU LISTE JSON ---
    private List<Map<String, Object>> apelClaudeLista(String promptSpecific, Set<String> existingTitles) {
        try {
            String raspunsClaude = trimitePromptLaClaude(promptSpecific);

            // Extragem doar partea de JSON (eliminăm dacă Claude zice "Iată lista:")
            int start = raspunsClaude.indexOf("[");
            int end = raspunsClaude.lastIndexOf("]");

            if (start == -1 || end == -1) {
                System.err.println("❌ Claude nu a returnat un Array JSON valid.");
                return new ArrayList<>();
            }

            String jsonCurat = raspunsClaude.substring(start, end + 1);
            return objectMapper.readValue(jsonCurat, new TypeReference<List<Map<String, Object>>>(){});

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // ==========================================
    // LOGICA APLICAȚIEI (Acum folosește Claude)
    // ==========================================

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

                dateGraf.append("📚 PROFILUL UTILIZATORULUI:\n");
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

            dateGraf.append("📖 RECOMANDĂRI POSIBILE DIN BAZA DE DATE (Pentru contextul tău):\n");
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
                String sender = msg.get("sender").equals("user") ? "Utilizatorul" : "Tu (AI)";
                historyStr.append(sender).append(": ").append(msg.get("text")).append("\n");
            }
        }

        String promptPentruClaude =
                "Ești Mentorul Literar personal al utilizatorului '" + username + "'. Ești un asistent conversațional, prietenos și empatic.\n\n" +
                        "ISTORICUL CONVERSAȚIEI:\n" + historyStr.toString() + "\n\n" +
                        "DATE EXTRASE DIN BAZA DE DATE:\n" + dateGraf.toString() + "\n\n" +
                        "MESAJUL NOU DE LA UTILIZATOR: \"" + mesajUtilizator + "\"\n\n" +
                        "REGULI CRITICE:\n" +
                        "1. MEMORIE: NU recomanda NICIODATĂ o carte deja menționată în istoric.\n" +
                        "2. STIL: Poartă un dialog natural, fără liste prea lungi.\n" +
                        "3. FORMAT OBLIGATORIU: Ori de câte ori recomanzi o carte specifică, ești OBLIGAT ABSOLUT să adaugi la sfârșitul mesajului acest format exact: [CARTE: Titlul Cărții | Nume Autor]. Exemplu: [CARTE: Dune | Frank Herbert]. Dacă sunt mai multe, pune mai multe tag-uri.";

        return trimitePromptLaClaude(promptPentruClaude);
    }

    public String genereazaTraseuStructurat(String subiect, int nrEtape, boolean stieBaze) {
        try {
            System.out.println("🤖 AGENT TRASEE: Caut cărți pentru: " + subiect + " | Știe bazele? " + stieBaze);

            String nivelInstructiune = stieBaze ?
                    "Utilizatorul a spus CĂ ȘTIE DEJA BAZELE acestui subiect. Sari peste cărțile pentru începători absolute. Începe direct cu nivelul INTERMEDIAR și continuă cu AVANSAT și EXPERT." :
                    "Utilizatorul este ÎNCEPĂTOR și nu știe bazele. Începe cu nivelul INTRODUCERE/PUNCT DE START, explică conceptele de bază și abia apoi crește dificultatea treptat.";

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

            String prompt = "Vreau să creezi un traseu de lectură despre: '" + subiect + "' în exact " + nrEtape + " etape logice. \n" +
                    nivelInstructiune + "\n\n" +
                    "Dacă e posibil, folosește și cărți din această listă: \n" + contextBazaDeDate.toString() + "\n" +
                    "Răspunde STRICT cu un Array JSON valid. FĂRĂ EXPLICAȚII. Structura: " +
                    "[ { \"nivel\": \"INTRODUCERE\", \"titlu_etapa\": \"...\", \"descriere\": \"...\", \"carti\": [ { \"titlu\": \"...\", \"autor\": \"...\", \"an\": 2024, \"descriere\": \"...\" } ] } ]";

            String raspunsClaude = trimitePromptLaClaude(prompt);

            int start = raspunsClaude.indexOf("[");
            int end = raspunsClaude.lastIndexOf("]");

            if (start != -1 && end != -1) {
                String finalJson = raspunsClaude.substring(start, end + 1);

                // =========================================================================
                // CORECTURĂ: SALVĂM TRASUUL ÎN MEMGRAPH CA SĂ APARĂ ÎN ISTORIC!
                // =========================================================================
                try (Session session = driver.session()) {
                    session.run("MERGE (tr:Traseu {subiect: $sub}) SET tr.json = $json",
                            Map.of("sub", subiect, "json", finalJson));
                }

                return finalJson;
            }
            return "[{\"titlu_etapa\": \"Eroare\", \"descriere\": \"Eroare la generare.\"}]";
        } catch (Exception e) {
            e.printStackTrace();
            return "[{\"titlu_etapa\": \"Eroare\", \"descriere\": \"Problemă internă.\"}]";
        }
    }

    public String cautaNoutatiInternet(List<String> interese) {
        String intereseUser = (interese == null || interese.isEmpty()) ? "Bestsellers" : String.join(", ", interese);
        String dataCurenta = java.time.LocalDate.now().toString();

        String prompt = "Astăzi este data de " + dataCurenta + ". Acționează ca secțiunea 'New Releases' a unei librării. " +
                "Vreau să îmi aduci lansări REALE de cărți care au apărut în ULTIMA LUNĂ (față de data de azi). " +
                "REGULĂ CRITICĂ: NU inventa cărți! Folosește doar cărți reale care chiar există. " +
                "Returnează EXACT un Array JSON cu exact 4 categorii. " +
                "Primele 3 categorii trebuie să fie genuri literare DIFERITE alese la întâmplare (ex: 'Istorie', 'Thriller', 'Sci-Fi' etc.) cu câte 4 cărți noi din ultima lună fiecare. " +
                "A patra categorie TREBUIE să se numească exact 'Recomandări pentru tine' și să conțină 4 cărți noi reale apărute în ultima lună, alese special pe baza acestor interese ale utilizatorului: " + intereseUser + ". " +
                "Răspunde DOAR cu Array-ul JSON (fără block de markdown, direct textul JSON). Format obligatoriu: " +
                "[{\"gen\": \"Nume Gen\", \"carti\": [{\"titlu\": \"Titlu Real\", \"autor\": \"Autor Real\", \"imagine\": \"https://placehold.co/150x220?text=Cover\", \"descriere\": \"...\"}]}]";

        String raspunsClaude = trimitePromptLaClaude(prompt);
        try {
            int start = raspunsClaude.indexOf("[");
            int end = raspunsClaude.lastIndexOf("]");
            if (start != -1 && end != -1) return raspunsClaude.substring(start, end + 1);
        } catch (Exception e) {}

        return "[]";
    }

    public String obtineDetaliiCompleteCarte(String titlu, String autor) {
        try {
            // 1. Verificăm dacă avem DEJA detaliile ample în Neo4j (Memgraph)
            try (Session session = driver.session()) {
                String checkQuery = "MATCH (c:Carte {titlu: $t}) RETURN c.descriere_ampla AS desc, c.despre_autor AS autor_info, c.teme_principale AS teme";
                var result = session.run(checkQuery, Map.of("t", titlu));

                if (result.hasNext()) {
                    var r = result.next();
                    if (r.get("desc") != null && !r.get("desc").isNull()) {
                        System.out.println("✅ Carte găsită în Neo4j! Returnez datele salvate.");
                        // Pregătim răspunsul din baza de date
                        Map<String, Object> raspunsDB = new HashMap<>();
                        raspunsDB.put("sursa_date", "DIN MEMGRAPH"); // Insigna pentru frontend
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

            // 2. Dacă nu o avem, o GENERĂM CU AI
            System.out.println("🤖 Cartea nu are detalii. Generez cu Claude...");
            String prompt = "Scrie detalii complexe pentru cartea '" + titlu + "' de " + autor + ". " +
                    "Trebuie să returnezi EXACT un obiect JSON cu următoarea structură, fără text pe lângă: " +
                    "{" +
                    "\"rezumat\": \"Un rezumat detaliat și o analiză de minim 150 cuvinte.\", " +
                    "\"despre_autor\": \"O scurtă biografie a autorului și expertiza sa.\", " +
                    "\"teme_principale\": [\"Tema 1\", \"Tema 2\", \"Tema 3\", \"Tema 4\", \"Tema 5\"]" +
                    "}";

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

                // Salvăm în Neo4j pentru click-ul viitor!
                try (Session session = driver.session()) {
                    String updateQuery = "MERGE (c:Carte {titlu: $t}) SET c.autor = $a, c.descriere_ampla = $desc, c.despre_autor = $autor_info, c.teme_principale = $teme";
                    session.run(updateQuery, Map.of("t", titlu, "a", autor, "desc", rezumat, "autor_info", despreAutor, "teme", teme));
                }

                // Creăm răspunsul pentru Frontend
                Map<String, Object> raspunsFinal = new HashMap<>();
                raspunsFinal.put("sursa_date", "GENERAT DE AI"); // Insigna pentru frontend
                raspunsFinal.put("rezumat", rezumat);
                raspunsFinal.put("despre_autor", despreAutor);
                raspunsFinal.put("teme_principale", teme);
                raspunsFinal.put("link_google", "https://www.google.com/search?tbm=bks&q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                raspunsFinal.put("link_amazon", "https://www.amazon.com/s?k=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));
                raspunsFinal.put("link_carturesti", "https://carturesti.ro/cautare?q=" + URLEncoder.encode(titlu + " " + autor, StandardCharsets.UTF_8));

                return objectMapper.writeValueAsString(raspunsFinal);
            }
            return "{\"eroare\": \"AI-ul nu a generat JSON-ul corect.\"}";

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"eroare\": \"A apărut o problemă la procesarea detaliilor.\"}";
        }
    }

    // ==========================================
    // METODE UTILITARE (Păstrate din varianta veche)
    // ==========================================

    private String getBookCoverUrl(String titlu, String autor) {
        try {
            // Curățăm termenii de căutare și combinăm simplu: "Titlu Autor"
            // Este o căutare mult mai flexibilă pentru Google Books decât parametrii stricți
            String interogare = titlu + " " + autor;
            String urlEncoded = URLEncoder.encode(interogare, StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + urlEncoded + "&maxResults=1";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36") // <-- TRUCUL: Mascăm Java ca fiind Google Chrome
                    .GET()
                    .build();

            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (r.statusCode() == 200) {
                JsonNode n = objectMapper.readTree(r.body());
                if (n.has("items") && n.get("items").size() > 0) {
                    JsonNode vol = n.get("items").get(0).path("volumeInfo");
                    if (vol.has("imageLinks")) {
                        String urlImagine = vol.path("imageLinks").path("thumbnail").asText();

                        // Google Books trimite link-uri cu http://. Le forțăm pe https:// ca să nu le blocheze browserul
                        urlImagine = urlImagine.replace("http://", "https://");

                        // TRUC EXTRA: Google Books tinde să pună uneori restricții de zoom.
                        // Ne asigurăm că link-ul este curat și valid pentru tag-ul <img>
                        return urlImagine;
                    }
                }
            } else {
                System.err.println("⚠️ Google Books a răspuns cu codul: " + r.statusCode() + " din cauza: " + r.body());
            }
        } catch (Exception e) {
            System.err.println("❌ Eroare la extragerea coperții pentru " + titlu + ": " + e.getMessage());
        }

        // Dacă Google totuși nu găsește cartea, generăm o copertă temporară elegantă cu un placeholder gri textat
        return "https://placehold.co/400x600/2a2a2a/ffffff?text=" + URLEncoder.encode(titlu, StandardCharsets.UTF_8);
    }

    private void salveazaInMemgraph(String titlu, String autor, String gen, String img, Map<String, Object> detalii) {
        try (Session s = driver.session()) {
            List<String> keywords = (List<String>) detalii.getOrDefault("cuvinte_cheie", new ArrayList<String>());
            Map<String, Object> params = new HashMap<>();
            params.put("t", titlu); params.put("autor", autor); params.put("gen", gen);
            params.put("img", img != null ? img : "https://placehold.co/300x450");
            params.put("desc", detalii.getOrDefault("descriere", "Fără descriere"));
            params.put("an", detalii.getOrDefault("an", 0)); params.put("editura", detalii.getOrDefault("editura", "-"));
            params.put("nr_pagini", detalii.getOrDefault("nr_pagini", 0)); params.put("limba", detalii.getOrDefault("limba", "Română"));
            params.put("kw", keywords);

            String query = "MERGE (c:Carte {titlu: $t}) SET c.autor=$autor, c.categoria=$gen, c.imagine=$img, c.descriere=$desc, c.an=$an, c.editura=$editura, c.nr_pagini=$nr_pagini, c.limba=$limba MERGE (au:Autor {nume: $autor}) MERGE (c)-[:SCRISA_DE]->(au) WITH c UNWIND $kw AS cuvant MERGE (t:Tag {nume: toLower(cuvant)}) MERGE (c)-[:ARE_TAG]->(t)";
            s.run(query, params);
        } catch (Exception e) {}
    }

    public void salveazaProfilComplet(String username, List<String> tags, List<String> experts, List<Map<String, String>> books) {
        try (Session session = driver.session()) {
            session.run("MERGE (u:Utilizator {username: $u})", Map.of("u", username));
            session.run("MATCH (u:Utilizator {username: $u})-[r:INTERESAT_DE]->() DELETE r", Map.of("u", username));

            if (tags != null) {
                for (String t : tags) {
                    if (t == null || t.trim().isEmpty()) continue;
                    session.run("MATCH (u:Utilizator {username: $u}) MERGE (tag:Tag {nume: $t}) MERGE (u)-[r:INTERESAT_DE]->(tag) SET r.score = 5.0", Map.of("u", username, "t", t.toLowerCase().trim()));
                }
            }

            if (books != null) {
                for (Map<String, String> b : books) {
                    String titlu = b.get("title"); String autor = b.get("author");
                    session.run("MATCH (u:Utilizator {username: $u}) MERGE (c:Carte {titlu: $titlu}) SET c.autor = $autor MERGE (u)-[r:A_CITIT]->(c) SET r.liked = true", Map.of("u", username, "titlu", titlu, "autor", autor));
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

    public String genereazaSiSalveaza(String gen) { return "Metodă dezactivată."; }
    public String genereazaPersonalizat(String sursa, String autor) { return "Metodă dezactivată."; }
    public String reparaDateLipsa() { return "Metodă dezactivată."; }
    public List<Map<String, String>> recomandaDupaTag(String input) { return new ArrayList<>(); }
}