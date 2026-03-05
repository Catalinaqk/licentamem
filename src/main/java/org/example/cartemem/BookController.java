package org.example.cartemem;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

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

    // --- 1. PAGINA PRINCIPALĂ (GALERIE) ---
    @GetMapping({"/", "/carti"})
    public String arataCartile(@RequestParam(value = "q", required = false) String query,
                               @RequestParam(value = "gen", required = false) String gen,
                               Model model) {
        return incarcaPagina(query, gen, model, "galerie");
    }

    // --- 2. SCANARE & STERGE (NOU) ---
    @GetMapping("/scaneaza")
    public String paginaScanare(Model model) {
        List<Map<String, String>> scanate = new ArrayList<>();
        try (Session session = driver.session()) {
            var result = session.run("MATCH (c:Carte {categoria: 'Scanata'})-[:SCRISA_DE]->(a:Autor) " +
                    "RETURN c.titlu AS titlu, c.imagine AS imagine, a.nume AS autor " +
                    "ORDER BY id(c) DESC");
            while (result.hasNext()) {
                var r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("imagine", r.get("imagine").asString());
                scanate.add(carte);
            }
        }
        model.addAttribute("cartiScanate", scanate);
        return "scaneaza";
    }

    @PostMapping("/api/delete-book")
    @ResponseBody
    public String stergeCarte(@RequestBody Map<String, String> payload) {
        String titlu = payload.get("titlu");
        try (Session session = driver.session()) {
            session.run("MATCH (c:Carte {titlu: $titlu}) DETACH DELETE c",
                    Map.of("titlu", titlu));
            return "✅ Cartea a fost ștearsă.";
        } catch (Exception e) {
            return "❌ Eroare la ștergere.";
        }
    }

    @PostMapping("/api/agent/scaneaza")
    @ResponseBody
    public String uploadPoza(@RequestBody Map<String, String> payload) {
        String rawBase64 = payload.get("imagine").split(",")[1];
        return bookAgent.recunoasteCarteDinPoza(rawBase64);
    }

    // --- 3. DETALII ---
    @GetMapping("/detalii")
    public String veziDetalii(@RequestParam("titlu") String titlu, Model model) {
        Map<String, Object> carte = new HashMap<>();
        try (Session session = driver.session()) {
            var result = session.run(
                    "MATCH (c:Carte {titlu: $titlu}) " +
                            "OPTIONAL MATCH (c)-[:SCRISA_DE]->(a:Autor) " +
                            "RETURN c, a.nume AS nume_autor",
                    Map.of("titlu", titlu)
            );

            if (result.hasNext()) {
                Record r = result.next();
                org.neo4j.driver.types.Node n = r.get("c").asNode();

                carte.put("titlu", n.get("titlu").asString("Titlu Necunoscut"));
                carte.put("autor", r.get("nume_autor").isNull() ? n.get("autor").asString("Necunoscut") : r.get("nume_autor").asString());
                carte.put("imagine", n.get("imagine").asString("https://placehold.co/300x450"));
                carte.put("categoria", n.get("categoria").asString("General"));
                carte.put("limba", n.get("limba").asString("Necunoscută"));
                carte.put("editura", n.get("editura").asString("-"));
                carte.put("an", n.get("an").asObject());

                if (n.containsKey("nr_pagini")) {
                    carte.put("nr_pagini", n.get("nr_pagini").asInt());
                } else {
                    carte.put("nr_pagini", 0);
                }

                carte.put("descriere_ampla", n.get("descriere_ampla").asString(""));
            }
        }
        model.addAttribute("carte", carte);
        return "detalii";
    }

    // --- 4. POPULARE & FAVORITE ---
    @GetMapping("/populare")
    public String paginaPopulare(Model model) {
        List<String> autori = List.of("Stephen King", "Colleen Hoover", "J.K. Rowling", "Agatha Christie", "Mircea Cărtărescu", "Haruki Murakami", "Irina Binder", "J.R.R. Tolkien", "Dan Brown");
        model.addAttribute("autori", autori);
        return incarcaPagina(null, "Bestseller", model, "populare");
    }

    // --- 5. ÎNVĂȚARE ---
    @GetMapping("/invata")
    public String paginaInvatare(@RequestParam(value = "domeniu", required = false) String domeniu, Model model) {
        String categorieCautata = (domeniu != null) ? "Invatare: " + domeniu : "Invatare";
        return incarcaPagina(null, categorieCautata, model, "invata");
    }

    @PostMapping("/api/agent/invata")
    @ResponseBody
    public String triggerLearningAgent(@RequestBody Map<String, String> payload) {
        return bookAgent.genereazaDrumInvatare(payload.get("obiectiv"));
    }

    // --- 6. API-URI ADMINISTRATIVE & AI ---
    @PostMapping("/api/agent/rezumat")
    @ResponseBody
    public String genereazaRezumat(@RequestBody Map<String, String> payload) {
        return bookAgent.genereazaRezumat(payload.get("titlu"), payload.get("autor"));
    }

    @PostMapping("/api/agent/auto-populeaza")
    @ResponseBody
    public String triggerAgent(@RequestBody Map<String, String> payload) {
        return bookAgent.genereazaSiSalveaza(payload.getOrDefault("gen", "Science Fiction"));
    }

    @PostMapping("/api/agent/custom")
    @ResponseBody
    public String triggerCustomAgent(@RequestBody Map<String, String> payload) {
        return bookAgent.genereazaPersonalizat(payload.get("sursa"), payload.get("autor"));
    }

    @PostMapping("/api/admin/repara-pagini")
    @ResponseBody
    public String reparaPagini() {
        return bookAgent.reparaDateLipsa();
    }

    // --- METODA PRIVATĂ DE ÎNCĂRCARE (RECONSTRUITĂ) ---
    private String incarcaPagina(String query, String gen, Model model, String templateName) {
        List<Map<String, String>> listaCarti = new ArrayList<>();
        List<String> listaGenuri = List.of("Bestseller", "Science Fiction", "Fantasy", "Horror", "Thriller", "Mister", "Romance", "Istorie", "Psihologie", "Scanata");

        try (Session session = driver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (c:Carte)-[:SCRISA_DE]->(a:Autor) WHERE 1=1 ");
            Map<String, Object> params = new HashMap<>();

            if (query != null && !query.isEmpty()) {
                cypher.append("AND (toLower(c.titlu) CONTAINS toLower($q) OR toLower(a.nume) CONTAINS toLower($q)) ");
                params.put("q", query);
            }
            if (gen != null && !gen.isEmpty()) {
                cypher.append("AND toLower(c.categoria) CONTAINS toLower($gen) ");
                params.put("gen", gen);
            }
            cypher.append("RETURN c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS desc, a.nume AS autor ORDER BY id(c) DESC LIMIT 500");

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
        }
        model.addAttribute("carti", listaCarti);
        model.addAttribute("genuri", listaGenuri);
        model.addAttribute("selectatGen", gen);
        model.addAttribute("cautare", query);
        return templateName;
    }
}