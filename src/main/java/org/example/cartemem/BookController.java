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
                               @RequestParam(value = "user", required = false) String username,
                               Model model) {

        // Dacă utilizatorul caută manual ceva, lăsăm codul tău existent să funcționeze
        if ((query != null && !query.isEmpty()) || (gen != null && !gen.isEmpty())) {
            return incarcaPagina(query, gen, model, "galerie");
        }

        List<Map<String, String>> listaCartiPersonalizate = new ArrayList<>();
        List<String> listaGenuri = List.of("Bestseller", "Science Fiction", "Fantasy", "Horror", "Thriller", "Mister", "Romance", "Istorie", "Psihologie", "Scanata");

        try (Session session = driver.session()) {
            // Dacă avem un utilizator logat, căutăm cărți pe baza intereselor LUI salvate în Memgraph
            if (username != null && !username.isEmpty()) {
                String queryPersonalizat =
                        "MATCH (u:Utilizator {username: $user}) " +
                                "OPTIONAL MATCH (u)-[:INTERESAT_DE]->(t:Tag)<-[:ARE_TAG]-(c1:Carte) " +
                                "OPTIONAL MATCH (u)-[:A_CITIT]->(citita:Carte)-[:ARE_TAG]->(:Tag)<-[:ARE_TAG]-(c2:Carte) " +
                                "WITH collect(c1) + collect(c2) AS toate " +
                                "UNWIND toate AS c " +
                                "MATCH (c)-[:SCRISA_DE]->(a:Autor) " +
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

            // Dacă lista e goală (nu e logat sau nu am găsit cărți pentru interese), arătăm cărțile tale standard
            if (listaCartiPersonalizate.isEmpty()) {
                return incarcaPagina(null, null, model, "galerie");
            }

        } catch (Exception e) {
            e.printStackTrace();
            return incarcaPagina(null, null, model, "galerie");
        }

        // Punem datele în model pentru pagina galerie.html
        model.addAttribute("carti", listaCartiPersonalizate);
        model.addAttribute("genuri", listaGenuri);
        model.addAttribute("selectatGen", "");
        model.addAttribute("cautare", "");
        model.addAttribute("esteCautare", false);

        return "galerie";
    }

    // --- Noul Endpoint pentru Sfatul Experților (GraphRAG) ---
    @PostMapping("/api/agent/experti-smart")
    @ResponseBody
    public String cereSfatulExpertilorSmart(@RequestBody Map<String, String> payload) {
        String username = payload.get("username");
        if (username == null || username.isEmpty()) {
            return "Te rog să te loghezi pentru a primi sfaturi personalizate!";
        }
        // Apelăm noua metodă din agent
        return bookAgent.genereazaRecomandareGraphRAG(username);
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


    // --- 3. DETALII ---
    @GetMapping("/detalii")
    public String veziDetalii(@RequestParam("titlu") String titlu, Model model) {
        Map<String, Object> carte = new HashMap<>();
        try (Session session = driver.session()) {
            String query = "MATCH (c:Carte {titlu: $titlu}) " +
                    "OPTIONAL MATCH (c)-[:SCRISA_DE]->(a:Autor) " +
                    "OPTIONAL MATCH (c)-[:PASUL_URMATOR]->(next:Carte) " +
                    "RETURN c, a.nume AS nume_autor, next.titlu AS titlu_urmator";

            var result = session.run(query, Map.of("titlu", titlu));

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

                // CORECURA ESTE AICI: Punem null explicit dacă nu există, pentru a nu crăpa Thymeleaf
                carte.put("titlu_urmator", r.get("titlu_urmator").isNull() ? null : r.get("titlu_urmator").asString());
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

    // --- 5. ÎNVĂȚARE (Modelul Simplu) ---
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

    // --- 6. SFATUL EXPERȚILOR (GraphRAG) ---
    @GetMapping("/sfatul-expertilor")
    public String paginaExperti(Model model) {
        return "experti";
    }

    @PostMapping("/api/agent/experti")
    @ResponseBody
    public String cereSfatulExpertilor(@RequestBody Map<String, Object> payload) {
        List<String> topicuri = (List<String>) payload.get("topicuri");
        String profil = (String) payload.get("profil");
        return bookAgent.recomandaPrinExperti(topicuri, profil);
    }

    // --- 7. TRASEE DE LECTURĂ ---
    @GetMapping("/trasee")
    public String paginaTrasee(Model model) {
        List<String> traseeSalvate = new ArrayList<>();
        try (Session session = driver.session()) {
            // ACUM ADUCEM DOAR NODURILE DE TIP 'Traseu' (Fără căutările vechi)
            var result = session.run("MATCH (t:Traseu) RETURN t.subiect AS domeniu ORDER BY t.subiect ASC");
            while(result.hasNext()) {
                traseeSalvate.add(result.next().get("domeniu").asString());
            }
        } catch (Exception e) {}
        model.addAttribute("traseeSalvate", traseeSalvate);
        return "trasee";
    }

    // --- ENDPOINT NOU: Pentru a încărca un traseu salvat când dăm click pe el ---
    @GetMapping("/api/agent/traseu-salvat")
    @ResponseBody
    public String getTraseuSalvat(@RequestParam("subiect") String subiect) {
        try (Session session = driver.session()) {
            var res = session.run("MATCH (t:Traseu {subiect: $sub}) RETURN t.json AS json", Map.of("sub", subiect));
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
        return bookAgent.genereazaTraseuStructurat(subiect, nrEtape);
    }

    // --- 8. API-URI ADMINISTRATIVE & AI ---
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

    @GetMapping("/api/smart-search")
    @ResponseBody
    public List<Map<String, String>> smartSearch(@RequestParam("q") String query) {
        return bookAgent.recomandaDupaTag(query);
    }

    @GetMapping("/recomandari")
    public String paginaRecomandari(@RequestParam(value = "tag", required = false) String tag, Model model) {
        List<Map<String, String>> rezultateFinale = new ArrayList<>();
        if (tag != null && !tag.isEmpty()) {
            rezultateFinale = bookAgent.gasesteRecomandariSmart(tag);
            if (rezultateFinale.size() < 2) {
                List<Map<String, String>> cartiAI = bookAgent.genereazaCartiPeSubiect(tag);
                rezultateFinale.addAll(cartiAI);
            }
            model.addAttribute("carti", rezultateFinale);
            model.addAttribute("cuvantCautat", tag);
        }
        return "recomandari";
    }

    // --- METODA PRIVATĂ DE ÎNCĂRCARE ---
    private String incarcaPagina(String query, String gen, Model model, String templateName) {
        List<Map<String, String>> listaCarti = new ArrayList<>();
        List<String> listaGenuri = List.of("Bestseller", "Science Fiction", "Fantasy", "Horror", "Thriller", "Mister", "Romance", "Istorie", "Psihologie", "Scanata");

        try (Session session = driver.session()) {
            StringBuilder cypher = new StringBuilder("MATCH (c:Carte)-[:SCRISA_DE]->(a:Autor) WHERE 1=1 ");
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