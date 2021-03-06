package org.nustaq.kontraktor.services.rlserver;

import com.mongodb.ConnectionString;
import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.bson.BsonString;
import org.bson.Document;
import org.nustaq.kontraktor.services.ClusterCfg;
import org.nustaq.kontraktor.services.ServiceRegistry;
import org.nustaq.kontraktor.services.rlclient.DataCfg;
import org.nustaq.kontraktor.services.rlclient.DataShard;
import org.nustaq.kontraktor.services.rlclient.DataShardArgs;
import org.nustaq.kontraktor.services.rlserver.mongodb.MongoPersistance;
import org.nustaq.reallive.api.RecordStorage;
import org.nustaq.reallive.api.TableDescription;
import org.nustaq.reallive.client.EmbeddedRealLive;
import org.nustaq.reallive.impl.storage.CachedOffHeapStorage;
import org.nustaq.reallive.impl.storage.HeapRecordStorage;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.nustaq.kontraktor.services.ServiceRegistry.parseCommandLine;

public class SingleProcessRLCluster {

    private static SingleProcessRLClusterArgs options;
    public static MongoClient mongo;
    public static MongoDatabase mongoDB;

    public static void main(String[] args) throws InterruptedException {
        if ( ! new File("./etc").exists() ) {
            System.out.println("Please start with working dir [project]");
            System.exit(1);
        }

        options = (SingleProcessRLClusterArgs) parseCommandLine(args,null,new SingleProcessRLClusterArgs());
        SimpleRLConfig scfg = SimpleRLConfig.read();

        if ( scfg.mongoConnection != null ) {
            EmbeddedRealLive.sCustomRecordStorage.put("MONGO", desc -> {
               return createOrConnectMongoDBStorage(desc,scfg);
            });
        }

        ClusterCfg cfg = new ClusterCfg();
        DataCfg datacfg = new DataCfg();
        datacfg.schema(scfg.tables);
        String dirs[] = new String[scfg.numNodes];
        for (int i = 0; i < dirs.length; i++) {
            dirs[i] = scfg.dataDir;
        }
        datacfg.dataDir(dirs);
        cfg.dataCluster(datacfg);

        // start Registry
        ServiceRegistry.start( options,cfg);
        Thread.sleep(1000);

        Executor ex = Executors.newCachedThreadPool();
        // Start Data Shards

        for ( int i = 0; i < cfg.getDataCluster().getNumberOfShards(); i++ ) {
            final int finalI = i;
            ex.execute(() -> DataShard.start(DataShardArgs.from(options,finalI)));
        }
    }

    protected static RecordStorage createOrConnectMongoDBStorage(TableDescription desc, SimpleRLConfig scfg) {
        synchronized (SingleProcessRLCluster.class) {
            if ( mongoDB == null ) {
                ConnectionString con = new ConnectionString(scfg.mongoConnection);
                mongo = MongoClients.create(scfg.mongoConnection);
                mongoDB = mongo.getDatabase(con.getDatabase());
            }
        }
        MongoCollection<Document> collection = mongoDB.getCollection(desc.getName());
        collection.createIndex(Indexes.hashed("key"));
        return new CachedOffHeapStorage(new MongoPersistance(collection,desc),new HeapRecordStorage());
    }

}

