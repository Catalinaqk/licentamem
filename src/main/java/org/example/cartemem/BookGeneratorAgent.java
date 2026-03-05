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

    // --- CHEIA TA ACTUALIZATĂ ---
    private final String GEMINI_API_KEY = "AIzaSyB8EQqw4GixfN4bWzsYCKr9GWjNOQsZNvQ";

    // --- MODELUL STABIL DIN LISTA TA (gemini-2.5-flash) ---
    private final String MODEL_NAME = "gemini-2.5-flash";
    private final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/" + MODEL_NAME + ":generateContent?key=" + GEMINI_API_KEY;

    public BookGeneratorAgent(Driver driver) {
        this.driver = driver;
    }

    // --- 1. Generare pe Gen (General) ---
    public String genereazaSiSalveaza(String gen) {
        String prompt = buildGenerationPrompt(gen, null, null);
        return executaFluxul(prompt, gen);
    }

    // --- 2. Generare Personalizată (Pagina Favorite) ---
    public String genereazaPersonalizat(String sursa, String autor) {
        String gen = "Bestseller";
        String prompt = buildGenerationPrompt(gen, sursa, autor);
        return executaFluxul(prompt, gen);
    }

    // --- 3. Generare Rezumat Detaliat (La cerere) ---
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

    // --- 4. Generare Drum Învățare (Learning Path) ---
    public String genereazaDrumInvatare(String obiectiv) {
        // Îi cerem AI-ului să fie un curator de cursuri
        String prompt = "Utilizatorul vrea să învețe de la zero despre: '" + obiectiv + "'. " +
                "Sugerează o listă de 10 cărți fundamentale, ordonate logic de la introducere (începători) la expert. " +
                "Include cele mai respectate titluri din domeniu. " +
                "Asigură-te că răspunzi STRICT cu un Array JSON valid, cu aceste câmpuri: " +
                "titlu, autor, an (int), editura, nr_pagini (int), descriere (max 20 cuvinte). " +
                "Fără explicații în afara JSON-ului.";

        // Refolosim executaFluxul, dar marcăm categoria special
        return executaFluxul(prompt, "Invatare: " + obiectiv);
    }

    // --- MOTORUL PRINCIPAL ---
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

    // --- CONSTRUCTOR PROMPT ȘI EXCLUDERE ---
    private String buildGenerationPrompt(String gen, String sursa, String autor) {
        StringBuilder prompt = new StringBuilder();

        // 1. Obținem titlurile existente pentru excludere
        Set<String> existingTitles = new HashSet<>();
        try (Driver tempDriver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("", ""))) {
            try (Session session = tempDriver.session()) {
                var result = session.run("MATCH (c:Carte) RETURN c.titlu AS titlu");
                while (result.hasNext()) existingTitles.add(result.next().get("titlu").asString());
            }
        } catch (Exception e) {
            System.out.println("Avertisment: Nu s-au putut exclude titlurile existente din cauza erorii Memgraph.");
        }

        prompt.append("Generează o listă JSON cu 5 cărți din genul '").append(gen).append("'. ");

        if (autor != null && !autor.trim().isEmpty() && !autor.equals("Oricare")) {
            prompt.append("scrise de autorul ").append(autor).append(" ");
        } else if (sursa != null) {
            prompt.append("care sunt cele mai vândute pe site-ul ").append(sursa).append(". ");
        }

        if (!existingTitles.isEmpty()) {
            // Instrucțiunea de excludere!
            String excludedTitles = String.join(", ", existingTitles);
            prompt.append(" Asigură-te că **NU incluzi** niciunul dintre următoarele titluri în răspunsul tău: [").append(excludedTitles).append("].");
        }

        prompt.append(" Include exact aceste câmpuri: titlu, autor, an (număr int), editura, nr_pagini (estimat int), descriere (scurtă, max 20 cuvinte). Răspunde STRICT cu un Array JSON valid.");

        return prompt.toString();
    }

    private List<Map<String, Object>> apelGeminiLista(String promptSpecific, Set<String> existingTitles) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", List.of(Map.of("parts", List.of(Map.of("text", promptSpecific)))));
            // FORȚĂM MODUL JSON
            requestBody.put("generationConfig", Map.of("responseMimeType", "application/json"));

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 429) {
                System.out.println("❌ EROARE CRITICĂ COTA (429): Cota Free Tier epuizată. Așteaptă 24h sau activează facturarea.");
                return new ArrayList<>();
            }
            if (resp.statusCode() != 200) {
                System.out.println("❌ EROARE HTTP GEMINI (" + resp.statusCode() + "): " + resp.body());
                return new ArrayList<>();
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode candidates = root.path("candidates");

            if (candidates.isEmpty()) return new ArrayList<>();

            String text = candidates.get(0).path("content").path("parts").get(0).path("text").asText();

            // Curățare agresivă
            int start = text.indexOf("[");
            int end = text.lastIndexOf("]");

            if (start == -1 || end == -1) {
                System.out.println("❌ Nu am găsit array JSON [] în răspuns.");
                return new ArrayList<>();
            }

            String jsonCurat = text.substring(start, end + 1);
            return objectMapper.readValue(jsonCurat, new TypeReference<List<Map<String, Object>>>(){});

        } catch (Exception e) {
            System.out.println("❌ EXCEPȚIE AGENT: Eroare la parsare JSON: " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    // ... (restul metodelor rămân la fel) ...

    // Metoda getBookCoverUrl (am inclus îmbunătățirea pentru căutare Titlu+Autor)
    private String getBookCoverUrl(String t, String a) {
        String query = "intitle:" + t + "+inauthor:" + a;

        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + q + "&maxResults=1";

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

        // Fallback: Afișăm Placeholder-ul cu titlul cărții
        try {
            return "https://placehold.co/400x600/e0e0e0/333333?text=" + URLEncoder.encode(t, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "https://placehold.co/400x600?text=Fara+Coperta";
        }
    }
    public String reparaDateLipsa() {
        int actualizate = 0;
        try (Session session = driver.session()) {
            // 1. Găsim cărțile care au 0 pagini sau valoare nulă
            var result = session.run("MATCH (c:Carte) WHERE c.nr_pagini IS NULL OR c.nr_pagini = 0 RETURN c.titlu AS titlu, c.autor AS autor");

            while (result.hasNext()) {
                var record = result.next();
                String titlu = record.get("titlu").asString();
                String autor = record.get("autor").asString();

                // 2. Interogăm Google Books pentru a găsi numărul de pagini
                int pagini = cautaNrPaginiOnline(titlu, autor);

                if (pagini > 0) {
                    session.run("MATCH (c:Carte {titlu: $t}) SET c.nr_pagini = $p",
                            Map.of("t", titlu, "p", pagini));
                    actualizate++;
                }
            }
        } catch (Exception e) {
            return "Eroare la reparare: " + e.getMessage();
        }
        return "✅ Am reușit să completez numărul de pagini pentru " + actualizate + " cărți!";
    }

    // Metodă privată care extrage DOAR numărul de pagini de la Google
    private int cautaNrPaginiOnline(String t, String a) {
        try {
            String query = URLEncoder.encode("intitle:" + t + " inauthor:" + a, StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + query + "&maxResults=1";

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
            // 1. Identificăm datele de bază
            String t = (String) detalii.getOrDefault("titlu", titlu);
            String a = (String) detalii.getOrDefault("autor", autor);

            // 2. Căutăm OBLIGATORIU datele oficiale de pe Google (Paginile corecte + Copertă clară)
            Map<String, Object> dateGoogle = cautaDateOficialeGoogle(t, a);

            int pagini = (int) dateGoogle.get("pagini");
            String imagineClara = (String) dateGoogle.get("imagine");

            // Editura: Dacă AI-ul a văzut-o în poză, o lăsăm. Dacă e "Necunoscut", luăm de la Google.
            String edituraAI = detalii.getOrDefault("editura", "Necunoscut").toString();
            String edituraFinala = (edituraAI.equalsIgnoreCase("Necunoscut") || edituraAI.equals("-"))
                    ? (String) dateGoogle.get("editura")
                    : edituraAI;

            // Anul: Dacă AI a dat 0, luăm de la Google.
            int anFinal = 0;
            Object anRaw = detalii.get("an");
            if (anRaw instanceof Number) anFinal = ((Number) anRaw).intValue();
            if (anFinal == 0) anFinal = (int) dateGoogle.get("an");

            // 3. Pregătim parametrii într-un HashMap (Map.of dă eroare la mai mult de 10 elemente!)
            Map<String, Object> params = new HashMap<>();
            params.put("t", t);
            params.put("autor", a);
            params.put("gen", gen);
            params.put("img", imagineClara);
            params.put("desc", detalii.getOrDefault("descriere", "Carte scanată."));
            params.put("an", anFinal);
            params.put("editura", edituraFinala);
            params.put("nr_pagini", pagini);
            params.put("limba", detalii.getOrDefault("limba", "Română"));

            s.run("MERGE (c:Carte {titlu: $t}) " +
                    "SET c.autor=$autor, c.categoria=$gen, c.imagine=$img, c.descriere=$desc, " +
                    "    c.an=$an, c.editura=$editura, c.nr_pagini=$nr_pagini, c.limba=$limba " +
                    "MERGE (au:Autor {nume: $autor}) " +
                    "MERGE (c)-[:SCRISA_DE]->(au)", params);

            System.out.println("✅ Salvat hibrid: " + t + " (" + pagini + " pagini)");
        } catch (Exception e) {
            System.err.println("❌ Eroare la salvarea in Memgraph: " + e.getMessage());
        }
    }
    private Map<String, Object> cautaDateOficialeGoogle(String t, String a) {
        Map<String, Object> date = new HashMap<>();
        date.put("pagini", 0);
        date.put("editura", "Necunoscută");
        date.put("an", 2024);
        date.put("imagine", "https://placehold.co/300x450");

        try {
            String query = URLEncoder.encode("intitle:\"" + t + "\" inauthor:\"" + a + "\"", StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + query + "&maxResults=1";

            HttpResponse<String> resp = httpClient.send(HttpRequest.newBuilder().uri(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

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
        } catch (Exception e) {
            System.err.println("⚠️ Google Books inaccesibil: " + e.getMessage());
        }
        return date;
    }
    private int extrageNrPaginiDeLaGoogle(String t, String a) {
        try {
            String query = URLEncoder.encode("intitle:" + t + " inauthor:" + a, StandardCharsets.UTF_8);
            String url = "https://www.googleapis.com/books/v1/volumes?q=" + query + "&maxResults=1";

            HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                JsonNode n = objectMapper.readTree(resp.body());
                if (n.has("items") && n.get("items").size() > 0) {
                    JsonNode info = n.get("items").get(0).path("volumeInfo");
                    if (info.has("pageCount")) {
                        return info.get("pageCount").asInt();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Google Books nu a returnat pagini pentru: " + t);
        }
        return 0;
    }


    // Adaugă această metodă în BookGeneratorAgent.java

    public String recunoasteCarteDinPoza(String base64Image) {
        try {
            // 1. Curățare Base64 (Eliminăm prefixul de browser dacă există)
            if (base64Image.contains(",")) {
                base64Image = base64Image.substring(base64Image.indexOf(",") + 1);
            }

            String prompt = "Analizează această poză de copertă. Identifică Titlul și Autorul. " +
                    "Răspunde STRICT JSON: {\"titlu\":\"...\", \"autor\":\"...\", \"editura\":\"...\", \"an\":0}";

            // 2. Construcție structură Multimodală
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> imagePart = Map.of("inline_data", Map.of(
                    "mime_type", "image/jpeg",
                    "data", base64Image
            ));

            Map<String, Object> contents = Map.of("parts", List.of(textPart, imagePart));

            Map<String, Object> requestBody = Map.of(
                    "contents", List.of(contents),
                    "generationConfig", Map.of("responseMimeType", "application/json")
            );

            String jsonPayload = objectMapper.writeValueAsString(requestBody);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();

            System.out.println("📸 Trimitere imagine către Gemini...");
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() != 200) {
                System.err.println("❌ Eroare API: " + resp.body());
                return "❌ Eroare Gemini (HTTP " + resp.statusCode() + ")";
            }

            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode candidate = root.path("candidates").get(0);

            if (candidate == null || candidate.path("content").path("parts").isEmpty()) {
                return "❌ AI-ul nu a putut citi imaginea.";
            }

            String aiJson = candidate.path("content").path("parts").get(0).path("text").asText();
            Map<String, Object> detalii = objectMapper.readValue(aiJson, new TypeReference<>() {});

            String titlu = (String) detalii.get("titlu");
            String autor = (String) detalii.get("autor");

            salveazaInMemgraph(titlu, autor, "Scanata", null, detalii);

            return "✅ Cartea '" + titlu + "' a fost identificată!";

        } catch (Exception e) {
            System.err.println("❌ Excepție: " + e.getMessage());
            return "❌ Eroare tehnică: " + e.getMessage();
        }
    }
}