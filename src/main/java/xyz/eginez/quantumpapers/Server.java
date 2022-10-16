package xyz.eginez.quantumpapers;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
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

@Controller("/api")
public class Server {
    private static Logger logger = org.slf4j.LoggerFactory.getLogger(Server.class);
    private static String INDEX_PATH = "data/index";

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

    /**
     * Search for term in request parameter
     */
    @Get("/knn")
    public Map<Paper, List<Paper>> search() throws IOException {
        //open index reader
        Map<Paper, List<Paper>> results = new HashMap<>();
        IndexSearcher searcher = new IndexSearcher(DirectoryReader.open(FSDirectory.open(Paths.get(INDEX_PATH))));
        for(Paper paper : parseEmbeddings("src/test/resources/papers.txt.json")) {
            var res = search(searcher, paper.embeddings, 10);
            results.put(new Paper(paper.id, paper.title, paper.arxiv_id, null), res);
        }
        return results;
    }

    /**
     * Open json like file and index it
     * receive the json file as a query parameter
     */
    @Get("/index")
    public String index(@QueryValue Optional<String> filePath, @QueryValue Optional<Boolean> recreateIndex) throws IOException {
        List<Document> allDocs = new ArrayList<>();
        for(Paper paper : parseEmbeddings(filePath.orElse("src/test/resources/papers.txt.json"))) {
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
        writeIndex(allDocs, recreateIndex.orElse(true));
        return "Indexed " + allDocs.size() + " documents";
    }

    private List<Paper> parseEmbeddings(String filePath) throws IOException {
        var papers = new ArrayList<Paper>();
        var file = new File(filePath);
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
    private void writeIndex(List<Document> allDocs, boolean recreateIndex) throws IOException {
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
        try(IndexWriter writer = new IndexWriter(indexDir, new IndexWriterConfig())) {
            writer.addDocuments(allDocs);
            writer.commit();
        }
        logger.info("Wrote {} documents to index", allDocs.size());
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

