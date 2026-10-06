package com.androidharness.app.local
import java.io.File
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
class MnnBundleStoreTest {
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    @Test fun interruptedBundleResumesAndSurvivesStoreRecreation(){
        val root=kotlin.io.path.createTempDirectory("mnn-bundle").toFile()
        try{
            val config="{\"model\":true}".toByteArray();val weight=ByteArray(4096){(it%251).toByte()}
            val model=LocalModelCatalog.mnnModel.copy(id="test-mnn",artifacts=listOf(LocalModelArtifact("config.json",config.size.toLong(),hash(config)),LocalModelArtifact("llm.mnn.weight",weight.size.toLong(),hash(weight))))
            fun store()=LocalModelStore(root){model.takeIf{it.id==model.id}}
            val s=store();s.request(model.id)
            val parts=s.bundleParts(model)
            parts[0].first.install(parts[0].second,ByteArrayInputStream(config),{false},{})
            try{parts[1].first.install(parts[1].second,ByteArrayInputStream(weight.copyOfRange(0,1024)),{false},{}) ;fail("Short download should remain incomplete")}catch(e:ModelDownloadException){assertTrue(e.retryable)}
            assertFalse(s.installed(model));assertEquals(1024L,s.info(model.id).partialBytes)
            val resumed=store();assertTrue(resumed.pending(model.id));val piece=resumed.bundleParts(model)[1]
            assertEquals(1024L,piece.first.preparePartial(piece.second))
            piece.first.install(piece.second,ByteArrayInputStream(weight.copyOfRange(1024,weight.size)),{false},{},1024)
            assertTrue(store().installed(model));assertEquals((config.size+weight.size).toLong(),store().info(model.id).installedBytes)
            store().remove(model.id);assertFalse(File(root,model.id).exists());assertFalse(store().pending(model.id))
        }finally{root.deleteRecursively()}
    }
    @Test fun invalidChecksumNeverMakesBundleReady(){
        val root=kotlin.io.path.createTempDirectory("mnn-invalid").toFile()
        try{
            val bytes="valid-config".toByteArray();val model=LocalModelCatalog.mnnModel.copy(id="test-mnn",artifacts=listOf(LocalModelArtifact("config.json",bytes.size.toLong(),hash(bytes))))
            val s=LocalModelStore(root){model.takeIf{it.id==model.id}};val p=s.bundleParts(model).single()
            try{p.first.install(p.second,ByteArrayInputStream(ByteArray(bytes.size)),{false},{});fail("Checksum must fail")}catch(e:ModelDownloadException){assertFalse(e.retryable)}
            assertFalse(s.installed(model));assertEquals(0L,s.info(model.id).partialBytes)
        }finally{root.deleteRecursively()}
    }
    @Test fun bundleFilenamesCannotEscapeModelFolder(){
        val root=kotlin.io.path.createTempDirectory("mnn-boundary").toFile()
        try{
            val model=LocalModelCatalog.mnnModel.copy(id="test-mnn",artifacts=listOf(LocalModelArtifact("../escape.bin",4,"0".repeat(64))))
            val s=LocalModelStore(root){model.takeIf{it.id==model.id}}
            try{s.bundleParts(model);fail("Traversal must fail")}catch(_:IllegalArgumentException){}
        }finally{root.deleteRecursively()}
    }
}
