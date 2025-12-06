package org.example.cartemem;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class MemgraphLoader implements CommandLineRunner {

    private final String MEMGRAPH_URI = "bolt://localhost:7687";
    private final String MEMGRAPH_USER = "";
    private final String MEMGRAPH_PASS = "";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public void run(String... args) throws Exception {
        System.out.println(">>> START ACTUALIZARE SMART (Imagini Google + Descrieri Template)...");

        // Verificăm dacă fișierul type.json există
        try {
            InputStream inputStream = new ClassPathResource("type.json").getInputStream();
            List<Map<String, Object>> booksFromFile = objectMapper.readValue(inputStream, new TypeReference<List<Map<String, Object>>>() {});

            try (Driver driver = GraphDatabase.driver(MEMGRAPH_URI, AuthTokens.basic(MEMGRAPH_USER, MEMGRAPH_PASS));
                 Session session = driver.session()) {

                // 1. Vedem ce cărți avem deja
                Set<String> existingTitles = new HashSet<>();
                var result = session.run("MATCH (c:Carte) RETURN c.titlu AS titlu");
                while (result.hasNext()) existingTitles.add(result.next().get("titlu").asString().toLowerCase().trim());

                System.out.println(">>> Cărți deja în bază: " + existingTitles.size());

                int addedCount = 0;
                for (Map<String, Object> book : booksFromFile) {
                    String titlu = (String) book.get("titlu");
                    String autor = (String) book.get("autor");

                    if (titlu == null || existingTitles.contains(titlu.toLowerCase().trim())) continue;

                    addedCount++;
                    System.out.printf("[NOU %d] Procesez: %s...\n", addedCount, titlu);

                    // A. Imagine (Google Books)
                    String imagine = getBookCoverUrl(titlu, autor);

                    // B. Descriere "Smart Template"
                    String descriere = generateTemplateDescription(book);

                    // C. Salvare
                    saveBook(session, titlu, autor, book, descriere, imagine);

                    Thread.sleep(50);
                }
                System.out.println(">>> GATA! Am adăugat " + addedCount + " cărți noi.");
            }
        } catch (Exception e) {
            System.out.println(">>> AVERTISMENT: Nu am putut citi sau procesa type.json. Eroare: " + e.getMessage());
        }
    }

    // ... (metode ajutătoare) ...

    private String generateTemplateDescription(Map<String, Object> book) {
        String titlu = (String) book.get("titlu");
        String autor = (String) book.get("autor");
        Object anObj = book.get("an");
        String an = (anObj != null) ? String.valueOf(anObj) : "un an necunoscut";
        String categorie = (String) book.get("categoria");

        int template = new Random().nextInt(3);

        if (template == 0) {
            return "Publicat inițial în " + an + ", romanul \"" + titlu + "\" este o operă definitorie pentru genul " + categorie + ", scrisă de " + autor + ".";
        } else if (template == 1) {
            return "O carte captivantă din categoria " + categorie + ". \"" + titlu + "\" de " + autor + " (apărută în " + an + ") rămâne o lectură esențială.";
        } else {
            return "\"" + titlu + "\" reprezintă contribuția autorului " + autor + " la genul " + categorie + ", fiind publicată în anul " + an + ".";
        }
    }

    private void saveBook(Session session, String titlu, String autor, Map<String, Object> bookData, String desc, String img) {
        if (bookData.containsKey("cuvinte cheie")) {
            bookData.put("cuvinte_cheie", bookData.get("cuvinte cheie"));
        }
        List<String> kw = (List<String>) bookData.getOrDefault("cuvinte_cheie", new ArrayList<>());

        String query =
                "MERGE (c:Carte {titlu: $titlu}) " +
                        "SET c.an = $an, c.editura = $editura, c.tara = $tara, c.limba = $limba, " +
                        "    c.categoria = $cat, c.descriere = $desc, c.imagine = $img " +
                        "MERGE (a:Autor {nume: $autor}) " +
                        "MERGE (c)-[:SCRISA_DE]->(a) " +
                        "WITH c " +
                        "FOREACH (k IN $kw | MERGE (t:Tag {nume: k}) MERGE (c)-[:ARE_TAG]->(t))";

        session.run(query, Map.of(
                "titlu", titlu, "autor", autor,
                "an", bookData.getOrDefault("an", 0),
                "editura", bookData.getOrDefault("editura", ""),
                "tara", bookData.getOrDefault("tara", ""),
                "limba", bookData.getOrDefault("limba", ""),
                "cat", bookData.getOrDefault("categoria", ""),
                "desc", desc, "img", img, "kw", kw
        ));
    }

    // Am preluat logica îmbunătățită Titlu+Autor pentru acoperire mai bună
    private String getBookCoverUrl(String t, String a) {
        String query = "intitle:" + t + "+inauthor:" + a;
        try {
            String q = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder().uri(URI.create("https://www.googleapis.com/books/v1/volumes?q=" + q + "&maxResults=1")).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(resp.body());
                if (root.has("items") && root.get("items").size() > 0) {
                    JsonNode vol = root.get("items").get(0).path("volumeInfo");
                    if (vol.has("imageLinks")) return vol.path("imageLinks").path("thumbnail").asText().replace("http://", "https://");
                }
            }
        } catch (Exception e) {}
        try {
            return "https://placehold.co/400x600/e0e0e0/333333?text=" + URLEncoder.encode(t, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "https://placehold.co/400x600?text=Fara+Coperta";
        }
    }
}