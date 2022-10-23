package xyz.eginez.quantumpapers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.simple.SimpleHttpResponseFactory;
import io.micronaut.views.View;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.*;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnVectorQuery;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Controller("/api")
public class Server {
    private static Logger logger = org.slf4j.LoggerFactory.getLogger(Server.class);
    private static String INDEX_PATH = "data/index";

    private static String EMBEDDING_PATH = "data/paper.txt.json";

    private static Map<Integer, Future> workers = new LinkedHashMap<>();

    private ExecutorService executorService = Executors.newFixedThreadPool(4);

    @Get("/ping")
    public String hello() {
        return "pong";
    }
   public record Paper(@JsonProperty String id, @JsonProperty String title, @JsonProperty("arxiv-id") String arxiv_id, @JsonProperty("emb") float[] embeddings) {
        @Override
        public String toString() {
            return "Paper{" +
                    "id='" + id + '\'' +
                    ", title='" + title + '\'' +
                    ", arxiv_id='" + arxiv_id + '\'' +
                    ", embeddings=" + Arrays.toString(embeddings) +
                    '}';
        }
   }

   @Get("/static/{file}")
   public HttpResponse staticFile(String file) throws IOException {
       Path path = Paths.get("src/main/resources/static/" + file);
       byte[] bytes = Files.readAllBytes(path);
       var res = HttpResponse.ok(bytes);
       if (file.endsWith(".html"))  res.getHeaders().add("Content-Type", "text/html");
       return res;
   }

    record node(@JsonProperty String id, @JsonProperty String label) {}
    record link(@JsonProperty String source, @JsonProperty String target) {}
    record graphResults(@JsonProperty List<node> nodes, @JsonProperty List<link> links) {}
    record persistedResults(@JsonProperty String arxivId, @JsonProperty String title, @JsonProperty List<Paper> similars) {}
    @Get(uri="/graph/data", produces = "application/json")
   public String graphData() throws IOException {
        logger.info("Reading json file");
        ObjectMapper mapper = new ObjectMapper();
        List<persistedResults> readPapers = new ArrayList<persistedResults>();
        readPapers = mapper.readValue(new File("data/results.json"), mapper.getTypeFactory().constructCollectionType(List.class, persistedResults.class));
        int maxVal = 5000;
        List<persistedResults> paginated = readPapers.subList(0, Math.min(maxVal, readPapers.size()));
        // map paginated persitedResults
        var nodes = new ArrayList<node>();
        var nodeIds = new HashSet<String>();
        var links = new ArrayList<link>();
        paginated.forEach(e -> {
            nodes.add(new node(e.arxivId, e.title));
            nodeIds.add(e.arxivId);
            links.addAll(e.similars().stream().filter(l -> !l.arxiv_id.equals(e.arxivId)).map(p -> new link(e.arxivId(), p.arxiv_id)).toList());
        });
        var filteredLinks = links.stream().filter(l -> nodeIds.contains(l.target)).toList();

        logger.info("returning size: {}", nodes.size());
        return mapper.writeValueAsString(new graphResults(nodes, filteredLinks));
   }

    /**
     * Search for term in request parameter
     */
    @Get("/knn")
    public String search() throws IOException, JsonProcessingException {
        var worker = executorService.submit(() -> {
            Map<Paper, List<Paper>> results = new LinkedHashMap<>();
            //open index reader
            IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_PATH))));
            for (Paper paper : parseEmbeddings(EMBEDDING_PATH)) {
                var res = search(searcher, paper.embeddings, 10);
                results.put(new Paper(paper.id, paper.title, paper.arxiv_id, null), res);
            }
            //save results to json file
            List<persistedResults> pr = new ArrayList<>();
            results.forEach((k, v) -> pr.add(new persistedResults(k.arxiv_id, k.title, v)));
            ObjectMapper mapper = new ObjectMapper();
            mapper.writeValue(new File("data/results.json"), pr);
            logger.info("Saved results to data/results.json, size: {}", pr.size());
            return results;
        });
        workers.put(worker.hashCode(), worker);
        return Integer.toString(worker.hashCode());
    }

    record Result(int totalPages, Map<Paper, List<Paper>> papers, int nextPage, String error) { }
    @Get("/results/{id}/{page}")
    public Result getResults(@QueryValue("id") String id, @QueryValue("page") String page) throws IOException, InterruptedException, ExecutionException {
        var pageSize = 50;
        var worker = workers.get(Integer.parseInt(id));
        if (worker == null) {
            return new Result(0, Map.of(), 0, "No such worker");
        }

        if (worker.isDone()) {
            var results = (Map<Paper, List<Paper>>) worker.get();
            var startIndex = Integer.parseInt(page) * pageSize;
            var pagedResults = paginate(results, startIndex, pageSize);
            return new Result(results.size() / pageSize, pagedResults, Integer.parseInt(page) + 1, null);
        } else {
            return new Result(0, Map.of(), 0, "not done");
        }
    }

    public <T> Map<T, List<T>>  paginate(Map<T, List<T>> results,  int startIndex, int pageSize) {
        var pageResult = results.keySet().stream().toList().subList(startIndex, startIndex + pageSize);
        var newResult = new HashMap<T, List<T>>();
        for ( T paper : pageResult) {
            newResult.put(paper, results.get(paper));
        }
        return newResult;
    }

    /**
     * Open json like file and index it
     * receive the json file as a query parameter
     */
    @Get("/index")
    public String index(@QueryValue Optional<String> filePath, @QueryValue Optional<Boolean> recreateIndex) throws IOException {
        List<Document> allDocs = new ArrayList<>();
        for(Paper paper : parseEmbeddings(filePath.orElse(EMBEDDING_PATH))) {
            Field id = new StringField("id", paper.id, Field.Store.YES);
            Field content = new StringField("title", paper.title, Field.Store.YES);
            Field arxiv_id = new StringField("arxiv_id", paper.arxiv_id, Field.Store.YES);
            Field embeddigns = new KnnVectorField("embeddings", paper.embeddings(), VectorSimilarityFunction.COSINE);
            Document doc = new Document();
            doc.add(content);
            doc.add(embeddigns);
            doc.add(arxiv_id);
            doc.add(id);
            allDocs.add(doc);
        }
        var workId = writeIndex(allDocs, recreateIndex.orElse(true));
        return Integer.toString(workId);
    }

    private List<Paper> parseEmbeddings(String filePath) throws IOException {
        var papers = new ArrayList<Paper>();
        var file = new File(filePath);
        logger.info("Parsing embeddings");
        new BufferedReader(new FileReader(file)).lines().forEach(line -> {
            try {
                var objectMapper = new ObjectMapper();
                var paper = objectMapper.readValue(line, Paper.class);
                papers.add(paper);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        });
        return papers;
    }



    /**
     * open index writer and write the documents
     */
    private int writeIndex(List<Document> allDocs, boolean recreateIndex) throws IOException {
        var indexPath = Paths.get(INDEX_PATH);
        if (recreateIndex) {
            logger.info("Deleting index at {}", indexPath);
            // delete all files in indexPath
            Files.walk(indexPath)
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .forEach(File::delete);
            Files.delete(indexPath);
        }
        FSDirectory indexDir = FSDirectory.open(indexPath);
        var work = executorService.submit(() -> {
            try(IndexWriter writer = new IndexWriter(indexDir, new IndexWriterConfig())) {
                writer.addDocuments(allDocs);
                writer.commit();
                logger.info("Wrote {} documents to index", allDocs.size());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        workers.put(work.hashCode(), work);
        return work.hashCode();
    }

    private List<Paper> search(IndexSearcher searcher, float[] embeddings, int k) throws IOException {
        var knnQuery = new KnnVectorQuery("embeddings", embeddings, k, null);
        var hits = searcher.search(knnQuery, k);
        var res = new ArrayList<Paper>();
        for(var hit : hits.scoreDocs) {
            var doc = searcher.doc(hit.doc);
            var id = doc.get("id");
            var title = doc.get("title");
            var arxiv_id = doc.get("arxiv_id");
            res.add(new Paper(id, title, arxiv_id, new float[]{}));
        }
        return res;
    }
}

