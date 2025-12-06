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
    private final String GEMINI_API_KEY = "AIzaSyAOAwp4W5F9i34SxW4ZBabY9VXlasnebFU";

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

    private void salveazaInMemgraph(String titlu, String autor, String gen, String img, Map<String, Object> detalii) {
        try (Driver tempDriver = GraphDatabase.driver("bolt://localhost:7687", AuthTokens.basic("", ""))) {
            try (Session s = tempDriver.session()) {
                s.run("MERGE (c:Carte {titlu: $t}) " +
                                "SET c.autor=$autor, c.categoria=$gen, c.imagine=$img, c.descriere=$desc, " +
                                "    c.an=$an, c.editura=$editura, c.nr_pagini=$nr_pagini " +
                                "MERGE (au:Autor {nume: $autor}) MERGE (c)-[:SCRISA_DE]->(au)",
                        Map.of(
                                "t", titlu,
                                "autor", detalii.getOrDefault("autor", "Necunoscut"),
                                "gen", gen,
                                "img", img,
                                "desc", detalii.getOrDefault("descriere", "Fără descriere"),
                                "an", detalii.getOrDefault("an", 2000),
                                "editura", detalii.getOrDefault("editura", "Necunoscută"),
                                "nr_pagini", detalii.getOrDefault("nr_pagini", 0)
                        ));
            }
        } catch (Exception e) {}
    }
}