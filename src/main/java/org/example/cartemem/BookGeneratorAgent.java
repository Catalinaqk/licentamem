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

    private final String GEMINI_API_KEY = "AIzaSyA1YvNSMO8WriR8Q3BvCcRoFqNcYfdeilg";
    private final String MODEL_NAME = "gemini-2.5-flash";
    private final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL_NAME + ":generateContent?key=" + GEMINI_API_KEY;

    public BookGeneratorAgent(Driver driver) {
        this.driver = driver;
    }

    public String genereazaSiSalveaza(String gen) {
        String prompt = buildGenerationPrompt(gen, null, null);
        return executaFluxul(prompt, gen);
    }

    public String genereazaPersonalizat(String sursa, String autor) {
        String gen = "Bestseller";
        String prompt = buildGenerationPrompt(gen, sursa, autor);
        return executaFluxul(prompt, gen);
    }

    public String genereazaRezumat(String titlu, String autor) {
        try {
            String prompt = "Scrie un rezumat detaliat (plot summary) și o analiză a temelor pentru cartea '" + titlu + "' de " + autor + ". " +
                    "Textul trebuie să fie în limba română, minim 200 de cuvinte, formatat frumos în paragrafe. Fără Markdown.";

            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
            ));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return "Eroare API (" + resp.statusCode() + "): " + resp.body();

            JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("candidates").isEmpty()) return "AI-ul nu a generat text (răspuns gol).";

            String text = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            try (Session session = driver.session()) {
                session.run("MATCH (c:Carte {titlu: $t}) SET c.descriere_ampla = $d", Map.of("t", titlu, "d", text));
            }
            return text;

        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare internă la generarea rezumatului.";
        }
    }

    public String genereazaDrumInvatare(String obiectiv) {
        String prompt = "Utilizatorul vrea să învețe de la zero despre: '" + obiectiv + "'. " +
                "Sugerează o listă de 5 cărți fundamentale, ordonate STRICT logic de la introducere (începători) la expert. " +
                "Include cele mai respectate titluri din domeniu. " +
                "Asigură-te că răspunzi STRICT cu un Array JSON valid, cu aceste câmpuri: " +
                "titlu, autor, an (int), editura, nr_pagini (int), descriere (max 20 cuvinte). " +
                "Fără explicații în afara JSON-ului.";

        return executaFluxulInvatare(prompt, "Invatare: " + obiectiv);
    }

    private String executaFluxulInvatare(String promptUser, String categorieSalvata) {
        try {
            System.out.println("🤖 AGENT ÎNVĂȚARE: Trimit cerere către " + MODEL_NAME + "...");
            List<Map<String, Object>> sugestii = apelGeminiLista(promptUser, new HashSet<>());
            if (sugestii.isEmpty()) return "⚠️ Eroare comunicare AI. Vezi consola.";

            StringBuilder raport = new StringBuilder("✅ Traseu de învățare creat:\n");
            String titluAnterior = null;

            for (Map<String, Object> carte : sugestii) {
                String titlu = (String) carte.get("titlu");
                String autor = (String) carte.get("autor");
                if (titlu == null) continue;

                String img = getBookCoverUrl(titlu, autor);
                salveazaInMemgraph(titlu, autor, categorieSalvata, img, carte);
                raport.append("- ").append(titlu).append("\n");

                if (titluAnterior != null) {
                    try (Session session = driver.session()) {
                        session.run("MATCH (a:Carte {titlu: $tAnt}), (b:Carte {titlu: $tCur}) " +
                                        "MERGE (a)-[:PASUL_URMATOR]->(b)",
                                Map.of("tAnt", titluAnterior, "tCur", titlu));
                    }
                }
                titluAnterior = titlu;
                Thread.sleep(100);
            }
            return raport.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare Critică Agent: " + e.getMessage();
        }
    }

    // --- TRASEU DE LECTURĂ FOLOSIND GraphRAG + SALVARE STRUCTURĂ ---
    public String genereazaTraseuStructurat(String subiect, int nrEtape) {
        try {
            System.out.println("🤖 AGENT TRASEE (GraphRAG): Caut cărți în graf pentru: " + subiect);

            // PASUL 1: RETRIEVAL (Extragem cărțile din Memgraph)
            StringBuilder contextBazaDeDate = new StringBuilder();
            try (Session session = driver.session()) {
                // Căutăm cărți care au legătură cu subiectul în titlu, categorie sau tag-uri
                String query = "MATCH (c:Carte) " +
                        "OPTIONAL MATCH (c)-[:ARE_TAG]->(t:Tag) " +
                        "WHERE toLower(c.categoria) CONTAINS toLower($subiect) " +
                        "   OR toLower(c.titlu) CONTAINS toLower($subiect) " +
                        "   OR toLower(t.nume) CONTAINS toLower($subiect) " +
                        "WITH DISTINCT c LIMIT 20 " +
                        "RETURN c.titlu AS titlu, c.autor AS autor, c.descriere AS descriere";

                var result = session.run(query, Map.of("subiect", subiect));

                while (result.hasNext()) {
                    var r = result.next();
                    contextBazaDeDate.append("- Titlu: '").append(r.get("titlu").asString())
                            .append("', Autor: ").append(r.get("autor").asString())
                            .append(", Descriere: ").append(r.get("descriere").asString()).append("\n");
                }
            }

            // PASUL 2: AUGMENTAREA PROMPT-ULUI
            String prompt;
            if (contextBazaDeDate.length() > 0) {
                // Dacă avem cărți în baza de date, le dăm lui Gemini
                System.out.println("📚 Am găsit cărți în baza de date. Le trimit către Gemini...");
                prompt = "Vreau să creezi un traseu de lectură de la zero la expert despre: '" + subiect + "'. " +
                        "Ai la dispoziție STRICT următoarele cărți din baza noastră de date:\n" +
                        contextBazaDeDate.toString() + "\n\n" +
                        "Alege cele mai potrivite cărți de mai sus și grupează-le logic în " + nrEtape + " etape. " +
                        "Dacă nu ai suficiente cărți pentru toate etapele, poți suplimenta cu 1-2 recomandări din cunoștințele tale generale.\n";
            } else {
                // Fallback dacă nu avem cărți pe subiect
                System.out.println("⚠️ Nu am găsit cărți în graf. Gemini va genera de la zero...");
                prompt = "Vreau să creezi un traseu de lectură de la zero la expert despre: '" + subiect + "'. " +
                        "Avem nevoie de exact " + nrEtape + " etape logice.\n";
            }

            // Cerem formatul JSON strict
            prompt += "Răspunde STRICT cu un Array JSON. Fiecare obiect din array reprezintă o etapă și trebuie să aibă structura: " +
                    "{ " +
                    "  \"nivel\": \"(ex: INTRODUCERE, FUNDAMENTE, INTERMEDIAR, AVANSAT, EXPERT)\", " +
                    "  \"titlu_etapa\": \"(ex: Primii pași în domeniu)\", " +
                    "  \"descriere\": \"(O scurtă propoziție despre ce învață utilizatorul la acest pas)\", " +
                    "  \"concepte\": [\"(3-4 cuvinte cheie scurte)\"], " +
                    "  \"carti\": [ " +
                    "       { \"titlu\": \"...\", \"autor\": \"...\", \"an\": 2020, \"editura\": \"...\", \"nr_pagini\": 300, \"descriere\": \"...\" } " +
                    "  ] " +
                    "} " +
                    "Generează maxim 2 cărți pentru fiecare etapă. Fără formatare Markdown, doar array-ul JSON.";

            // PASUL 3: GENERATION (Apelăm Gemini)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            requestBody.put("generationConfig", Map.of("responseMimeType", "application/json"));

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                return "[{\"titlu_etapa\": \"Eroare API\", \"descriere\": \"Eroare de la Gemini: " + resp.statusCode() + "\"}]";
            }

            JsonNode root = objectMapper.readTree(resp.body());
            String aiJson = root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

            // Salvarea cărților în graf
            List<Map<String, Object>> etape = objectMapper.readValue(aiJson, new TypeReference<List<Map<String, Object>>>(){});
            String categorieSalvata = "Invatare: " + subiect;

            for (Map<String, Object> etapa : etape) {
                List<Map<String, Object>> cartiEtapa = (List<Map<String, Object>>) etapa.get("carti");

                if (cartiEtapa != null) {
                    for (Map<String, Object> detaliiCarte : cartiEtapa) {
                        String titluCarte = (String) detaliiCarte.get("titlu");
                        String autorCarte = (String) detaliiCarte.get("autor");

                        String img = getBookCoverUrl(titluCarte, autorCarte);
                        detaliiCarte.put("imagine_generata", img);

                        salveazaInMemgraph(titluCarte, autorCarte, categorieSalvata, img, detaliiCarte);
                    }
                }
            }

            // PASUL 4: SALVAREA STRUCTURII COMPLETE A TRASEULUI (Pentru meniul din stânga)
            String finalJson = objectMapper.writeValueAsString(etape);
            try (Session session = driver.session()) {
                // Creăm un nod dedicat pentru Traseu care conține tot JSON-ul generat
                session.run("MERGE (tr:Traseu {subiect: $sub}) SET tr.json = $json",
                        Map.of("sub", subiect, "json", finalJson));
            }

            // Returnăm AI JSON actualizat cu tot cu imagini
            return finalJson;

        } catch (Exception e) {
            e.printStackTrace();
            return "[]";
        }
    }

    // --- GRAPHRAG: SFATUL EXPERȚILOR ---
    public String recomandaPrinExperti(List<String> topicuri, String profil) {
        try {
            StringBuilder contextGraf = new StringBuilder();

            try (Session session = driver.session()) {
                String query = "MATCH (e:Expert)-[r:RECOMANDA]->(c:Carte)-[:ARE_TAG]->(t:Tag) " +
                        "WHERE t.nume IN $topicuri " +
                        "RETURN e.nume AS expert, r.motiv AS motiv, c.titlu AS carte, c.autor AS autor, t.nume AS topic " +
                        "LIMIT 10";

                var result = session.run(query, Map.of("topicuri", topicuri));
                while (result.hasNext()) {
                    var rec = result.next();
                    contextGraf.append("- Expertul ").append(rec.get("expert").asString())
                            .append(" recomandă cartea '").append(rec.get("carte").asString())
                            .append("' de ").append(rec.get("autor").asString())
                            .append(" (domeniu: ").append(rec.get("topic").asString()).append(") ")
                            .append("motivând astfel: ").append(rec.get("motiv").asString()).append("\n");
                }
            }

            if (contextGraf.length() == 0) {
                contextGraf.append("Nu avem încă recomandări salvate în baza de date de la experți pentru aceste domenii exacte. Bazează-te pe cunoștințele tale generale, dar menționează că sunt recomandări AI, nu de la experții platformei.");
            }

            String prompt = "Ești un consilier literar de elită. Utilizatorul are profilul: '" + profil + "' și caută cărți care combină domeniile: " + topicuri.toString() + ".\n\n" +
                    "Iată ce spun experții noștri din baza de date despre cărțile relevante:\n" + contextGraf.toString() + "\n\n" +
                    "Scrie un mesaj prietenos, la persoana a doua, recomandându-i 1 sau 2 cărți. " +
                    "Folosește strict motivele experților de mai sus pentru a justifica recomandarea față de profilul lui. " +
                    "Formatează răspunsul tău în HTML simplu (folosește <b> pentru titluri și experți, și <br><br> pentru paragrafe nouă). Fără markdown de tip ```html.";

            String jsonBody = objectMapper.writeValueAsString(Map.of(
                    "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
            ));

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) return "<b>Eroare API:</b> " + resp.body();

            JsonNode root = objectMapper.readTree(resp.body());
            if (root.path("candidates").isEmpty()) return "Nu s-a putut genera un răspuns.";

            return root.path("candidates").get(0).path("content").path("parts").get(0).path("text").asText();

        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare la interogarea experților: " + e.getMessage();
        }
    }

    private String executaFluxul(String promptUser, String categorieSalvata) {
        try {
            System.out.println("🤖 AGENT: Trimit cerere către modelul " + MODEL_NAME + "...");
            Set<String> existente = new HashSet<>();
            try (Session session = driver.session()) {
                var res = session.run("MATCH (c:Carte) RETURN c.titlu AS titlu");
                while (res.hasNext()) existente.add(res.next().get("titlu").asString().toLowerCase().trim());
            }

            List<Map<String, Object>> sugestii = apelGeminiLista(promptUser, existente);
            if (sugestii.isEmpty()) return "⚠️ Eroare comunicare AI. Vezi consola pentru detalii.";

            int adaugate = 0;
            StringBuilder raport = new StringBuilder("✅ Cărți adăugate:\n");

            for (Map<String, Object> carte : sugestii) {
                String titlu = (String) carte.get("titlu");
                String autor = (String) carte.get("autor");
                if (titlu == null || existente.contains(titlu.toLowerCase().trim())) continue;

                String img = getBookCoverUrl(titlu, autor);
                salveazaInMemgraph(titlu, autor, categorieSalvata, img, carte);

                raport.append("- ").append(titlu).append("\n");
                adaugate++;
                Thread.sleep(100);
            }

            if (adaugate == 0) return "⚠️ Toate cărțile găsite existau deja.";
            return raport.toString();

        } catch (Exception e) {
            e.printStackTrace();
            return "Eroare Critică Agent: " + e.getMessage();
        }
    }

    private String buildGenerationPrompt(String gen, String sursa, String autor) {
        StringBuilder prompt = new StringBuilder();
        Set<String> existingTitles = new HashSet<>();
        try (Driver tempDriver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("", ""))) {
            try (Session session = tempDriver.session()) {
                var result = session.run("MATCH (c:Carte) RETURN c.titlu AS titlu");
                while (result.hasNext()) existingTitles.add(result.next().get("titlu").asString());
            }
        } catch (Exception e) {}

        prompt.append("Generează o listă JSON cu 5 cărți din genul '").append(gen).append("'. ");
        if (autor != null && !autor.trim().isEmpty() && !autor.equals("Oricare")) {
            prompt.append("scrise de autorul ").append(autor).append(" ");
        } else if (sursa != null) {
            prompt.append("care sunt cele mai vândute pe site-ul ").append(sursa).append(". ");
        }
        if (!existingTitles.isEmpty()) {
            String excludedTitles = String.join(", ", existingTitles);
            prompt.append(" Asigură-te că **NU incluzi** niciunul dintre următoarele titluri: [").append(excludedTitles).append("].");
        }
        prompt.append(" Include exact aceste câmpuri: titlu, autor, an (număr int), editura, nr_pagini (estimat int), descriere (scurtă, max 20 cuvinte). Răspunde STRICT cu un Array JSON valid.");
        return prompt.toString();
    }

    private List<Map<String, Object>> apelGeminiLista(String promptSpecific, Set<String> existingTitles) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(Map.of("parts", List.of(Map.of("text", promptSpecific)))));
            requestBody.put("generationConfig", Map.of("responseMimeType", "application/json"));
            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 429) {
                System.out.println("❌ EROARE COTA (429)");
                return new ArrayList<>();
            }
            if (resp.statusCode() != 200) return new ArrayList<>();

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode candidates = root.path("candidates");
            if (candidates.isEmpty()) return new ArrayList<>();

            String text = candidates.get(0).path("content").path("parts").get(0).path("text").asText();
            int start = text.indexOf("[");
            int end = text.lastIndexOf("]");
            if (start == -1 || end == -1) return new ArrayList<>();

            String jsonCurat = text.substring(start, end + 1);
            return objectMapper.readValue(jsonCurat, new TypeReference<List<Map<String, Object>>>(){});

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    private String getBookCoverUrl(String t, String a) {
        String query = "intitle:" + t + "+inauthor:" + a;
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "[https://www.googleapis.com/books/v1/volumes?q=](https://www.googleapis.com/books/v1/volumes?q=)" + q + "&maxResults=1";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> r = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (r.statusCode() == 200) {
                JsonNode n = objectMapper.readTree(r.body());
                if (n.has("items") && n.get("items").size() > 0) {
                    JsonNode vol = n.get("items").get(0).path("volumeInfo");
                    if (vol.has("imageLinks")) {
                        return vol.path("imageLinks").path("thumbnail").asText().replace("http://", "https://");
                    }
                }
            }
        } catch (Exception e) {}

        try {
            return "[https://placehold.co/400x600/e0e0e0/333333?text=](https://placehold.co/400x600/e0e0e0/333333?text=)" + URLEncoder.encode(t, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[https://placehold.co/400x600?text=Fara+Coperta](https://placehold.co/400x600?text=Fara+Coperta)";
        }
    }

    public String reparaDateLipsa() {
        int actualizate = 0;
        try (Session session = driver.session()) {
            var result = session.run("MATCH (c:Carte) WHERE c.nr_pagini IS NULL OR c.nr_pagini = 0 RETURN c.titlu AS titlu, c.autor AS autor");
            while (result.hasNext()) {
                var record = result.next();
                String titlu = record.get("titlu").asString();
                String autor = record.get("autor").asString();
                int pagini = cautaNrPaginiOnline(titlu, autor);
                if (pagini > 0) {
                    session.run("MATCH (c:Carte {titlu: $t}) SET c.nr_pagini = $p", Map.of("t", titlu, "p", pagini));
                    actualizate++;
                }
            }
        } catch (Exception e) {
            return "Eroare la reparare: " + e.getMessage();
        }
        return "✅ Am reușit să completez numărul de pagini pentru " + actualizate + " cărți!";
    }

    private int cautaNrPaginiOnline(String t, String a) {
        try {
            String query = URLEncoder.encode("intitle:" + t + " inauthor:" + a, StandardCharsets.UTF_8);
            String url = "[https://www.googleapis.com/books/v1/volumes?q=](https://www.googleapis.com/books/v1/volumes?q=)" + query + "&maxResults=1";
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode n = objectMapper.readTree(resp.body());
                if (n.has("items")) {
                    JsonNode vol = n.get("items").get(0).path("volumeInfo");
                    if (vol.has("pageCount")) {
                        return vol.get("pageCount").asInt();
                    }
                }
            }
        } catch (Exception e) {}
        return 0;
    }

    private void salveazaInMemgraph(String titlu, String autor, String gen, String img, Map<String, Object> detalii) {
        try (Session s = driver.session()) {
            List<String> keywords = (List<String>) detalii.getOrDefault("cuvinte_cheie", new ArrayList<String>());
            Map<String, Object> params = new HashMap<>();
            params.put("t", titlu);
            params.put("autor", autor);
            params.put("gen", gen);
            params.put("img", img != null ? img : "[https://placehold.co/300x450](https://placehold.co/300x450)");
            params.put("desc", detalii.getOrDefault("descriere", "Fără descriere"));
            params.put("an", detalii.getOrDefault("an", 0));
            params.put("editura", detalii.getOrDefault("editura", "-"));
            params.put("nr_pagini", detalii.getOrDefault("nr_pagini", 0));
            params.put("limba", detalii.getOrDefault("limba", "Română"));
            params.put("kw", keywords);

            String query =
                    "MERGE (c:Carte {titlu: $t}) " +
                            "SET c.autor=$autor, c.categoria=$gen, c.imagine=$img, c.descriere=$desc, " +
                            "    c.an=$an, c.editura=$editura, c.nr_pagini=$nr_pagini, c.limba=$limba " +
                            "MERGE (au:Autor {nume: $autor}) " +
                            "MERGE (c)-[:SCRISA_DE]->(au) " +
                            "WITH c " +
                            "UNWIND $kw AS cuvant " +
                            "MERGE (t:Tag {nume: toLower(cuvant)}) " +
                            "MERGE (c)-[:ARE_TAG]->(t)";

            s.run(query, params);
            System.out.println("✅ Salvat în graf: " + titlu);
        } catch (Exception e) {
            System.err.println("❌ Eroare Memgraph: " + e.getMessage());
        }
    }

    private Map<String, Object> cautaDateOficialeGoogle(String t, String a) {
        Map<String, Object> date = new HashMap<>();
        date.put("pagini", 0);
        date.put("editura", "Necunoscută");
        date.put("an", 2024);
        date.put("imagine", "[https://placehold.co/300x450](https://placehold.co/300x450)");
        try {
            String query = URLEncoder.encode("intitle:\"" + t + "\" inauthor:\"" + a + "\"", StandardCharsets.UTF_8);
            String url = "[https://www.googleapis.com/books/v1/volumes?q=](https://www.googleapis.com/books/v1/volumes?q=)" + query + "&maxResults=1";
            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode items = objectMapper.readTree(resp.body()).path("items");
                if (items.isArray() && items.size() > 0) {
                    JsonNode info = items.get(0).path("volumeInfo");
                    date.put("pagini", info.path("pageCount").asInt(0));
                    date.put("editura", info.path("publisher").asText("Necunoscută"));
                    String pDate = info.path("publishedDate").asText("2024");
                    date.put("an", Integer.parseInt(pDate.length() >= 4 ? pDate.substring(0, 4) : "2024"));
                    if (info.has("imageLinks")) {
                        date.put("imagine", info.path("imageLinks").path("thumbnail").asText().replace("http:", "https:"));
                    }
                }
            }
        } catch (Exception e) {}
        return date;
    }

    private int extrageNrPaginiDeLaGoogle(String t, String a) {
        return cautaNrPaginiOnline(t, a);
    }



    public List<Map<String, String>> recomandaDupaTag(String inputUtilizator) {
        List<Map<String, String>> rezultate = new ArrayList<>();
        String query = "MATCH (t:Tag)-[:ARE_TAG]-(c:Carte)-[:SCRISA_DE]->(a:Autor) WHERE toLower(t.nume) CONTAINS toLower($input) RETURN c.titlu AS titlu, c.imagine AS imagine, a.nume AS autor, t.nume AS tag_gasit LIMIT 10";
        try (Session session = driver.session()) {
            var result = session.run(query, Map.of("input", inputUtilizator));
            while (result.hasNext()) {
                var r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("imagine", r.get("imagine").asString());
                carte.put("tag", r.get("tag_gasit").asString());
                rezultate.add(carte);
            }
        }
        return rezultate;
    }

    public List<Map<String, String>> gasesteRecomandariSmart(String inputUtilizator) {
        List<Map<String, String>> recomandari = new ArrayList<>();
        String cypherQuery = "MATCH (t:Tag)<-[:ARE_TAG]-(c:Carte)-[:SCRISA_DE]->(a:Autor) WHERE toLower(t.nume) CONTAINS toLower($input) OR toLower(c.titlu) CONTAINS toLower($input) RETURN DISTINCT c.titlu AS titlu, c.imagine AS imagine, a.nume AS autor LIMIT 10";
        try (Session session = driver.session()) {
            var result = session.run(cypherQuery, Map.of("input", inputUtilizator));
            while (result.hasNext()) {
                var r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("imagine", r.get("imagine").asString());
                recomandari.add(carte);
            }
        }
        return recomandari;
    }

    public List<Map<String, String>> genereazaCartiPeSubiect(String subiect) {
        String prompt = "Utilizatorul vrea să învețe despre: '" + subiect + "'. Sugerează 5 cărți reale fundamentale. Răspunde STRICT JSON Array. FIECARE obiect trebuie să includă: titlu, autor, an (int), editura, descriere (max 20 cuvinte) și un câmp 'cuvinte_cheie' care să fie o listă de minim 4 string-uri.";
        List<Map<String, Object>> sugestiiAI = apelGeminiLista(prompt, new HashSet<>());
        List<Map<String, String>> rezultateNoi = new ArrayList<>();
        for (Map<String, Object> s : sugestiiAI) {
            String t = (String) s.get("titlu");
            String a = (String) s.get("autor");
            String img = getBookCoverUrl(t, a);
            salveazaInMemgraph(t, a, "Invatare: " + subiect, img, s);
            Map<String, String> carteMap = new HashMap<>();
            carteMap.put("titlu", t);
            carteMap.put("autor", a);
            carteMap.put("imagine", img);
            rezultateNoi.add(carteMap);
        }
        return rezultateNoi;
    }
}