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

    // --- Noul Endpoint Conversațional (GraphRAG) ---
    @PostMapping("/api/agent/experti-smart")
    @ResponseBody
    public String cereSfatulExpertilorSmart(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        String mesaj = (String) payload.get("mesaj");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> istoric = (List<Map<String, String>>) payload.get("istoric");

        if (username == null || username.isEmpty()) {
            return "Te rog să te loghezi pentru a primi sfaturi personalizate!";
        }

        return bookAgent.genereazaRecomandareGraphRAG(username, mesaj, istoric);
    }

    // SALVAREA PROFILULUI (Prinde datele din Frontend)
    @PostMapping("/api/utilizator/actualizeaza-profil")
    @ResponseBody
    public String actualizeazaProfil(@RequestBody Map<String, Object> payload) {
        String username = (String) payload.get("username");
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) payload.get("tags");
        @SuppressWarnings("unchecked")
        List<String> experts = (List<String>) payload.get("experts"); // Experții adăugați
        @SuppressWarnings("unchecked")
        List<Map<String, String>> books = (List<Map<String, String>>) payload.get("books");

        bookAgent.salveazaProfilComplet(username, tags, experts, books);
        return "Succes";
    }

    // ÎNCĂRCAREA PROFILULUI (Trimite datele înapoi când deschizi pagina)
    @GetMapping("/api/utilizator/profil")
    @ResponseBody
    public Map<String, Object> incarcaProfil(@RequestParam String username) {
        return bookAgent.incarcaProfil(username);
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
        @SuppressWarnings("unchecked")
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

    // --- API NOU: Returnează lista cu subiectele traseelor deja salvate ---
    @GetMapping("/api/trasee/salvate")
    @ResponseBody
    public List<String> getToateTraseeleSalvate() {
        List<String> trasee = new ArrayList<>();
        try (Session session = driver.session()) {
            var result = session.run("MATCH (t:Traseu) RETURN t.subiect AS domeniu ORDER BY t.subiect ASC");
            while(result.hasNext()) {
                trasee.add(result.next().get("domeniu").asString());
            }
        } catch (Exception e) {}
        return trasee;
    }

    @PostMapping("/api/carti/noutati-internet")
    @ResponseBody
    public String getNoutatiDePeInternet(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<String> interese = (List<String>) payload.get("interese");

        // Trimitem lista de interese către Agent ca să caute pe internet
        return bookAgent.cautaNoutatiInternet(interese);
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

    // --- API NOU: Trimite toate cărțile reale către interfață ---
    @GetMapping("/api/carti/toate")
    @ResponseBody
    public List<Map<String, String>> getToateCartile() {
        List<Map<String, String>> listaCarti = new ArrayList<>();
        try (Session session = driver.session()) {
            // AM MĂRIT LIMITA LA 1000 CA SĂ ADUCĂ ABSOLUT TOATE CĂRȚILE TALE!
            var result = session.run("MATCH (c:Carte)-[:SCRISA_DE]->(a:Autor) " +
                    "RETURN DISTINCT c.titlu AS titlu, c.imagine AS imagine, c.categoria AS categorie, c.descriere AS descriere, a.nume AS autor " +
                    "ORDER BY id(c) DESC LIMIT 1000");
            while (result.hasNext()) {
                var r = result.next();
                Map<String, String> carte = new HashMap<>();
                carte.put("titlu", r.get("titlu").asString());
                carte.put("autor", r.get("autor").asString());
                carte.put("categorie", r.get("categorie").asString());
                carte.put("imagine", r.get("imagine").asString());
                var desc = r.get("descriere");
                carte.put("descriere", (desc.isNull() || desc.asString().isEmpty()) ? "Fără descriere" : desc.asString());
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
            // Luăm toți autorii unici
            var resAutori = session.run("MATCH (a:Autor) RETURN DISTINCT a.nume AS nume ORDER BY a.nume");
            while(resAutori.hasNext()) autori.add(resAutori.next().get("nume").asString());

            // Luăm toate genurile (categoriile) unice
            var resGenuri = session.run("MATCH (c:Carte) WHERE c.categoria IS NOT NULL RETURN DISTINCT c.categoria AS nume ORDER BY c.categoria");
            while(resGenuri.hasNext()) genuri.add(resGenuri.next().get("nume").asString());
        }
        return Map.of("autori", autori, "genuri", genuri);
    }

}