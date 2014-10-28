package com.github.jmccrae.yuzu

import com.github.jmccrae.yuzu.YuzuSettings._
import com.github.jmccrae.yuzu.YuzuUserText._
import com.hp.hpl.jena.graph.{NodeFactory, Triple, TripleMatch, Node, Graph}
import com.hp.hpl.jena.rdf.model.{Model, ModelFactory, AnonId, Resource}
import com.hp.hpl.jena.query.{QueryExecutionFactory, QueryFactory}
import gnu.getopt.Getopt
import java.io.FileInputStream
import java.sql.{DriverManager, PreparedStatement, ResultSet, SQLException}
import java.util.concurrent.{Executors, TimeoutException, TimeUnit}
import java.util.zip.GZIPInputStream

// Abstarct backend

trait SPARQLResult {
  def result : Either[String, Model]
  def resultType : ResultType
}

trait Backend {
  def query(query : String, mimeType : ResultType, defaultGraphURI : Option[String],
    timeout : Int = 10) : SPARQLResult
  def lookup(id : String) : Option[Model]
  def listResources(offset : Int, limit : Int, prop : Option[String] = None, obj : Option[String] = None) : (Boolean,Seq[String])
  def listValues(offset : Int, limit : Int, prop : String) : (Boolean,Seq[String])
  def list(subj : Option[String], prop : Option[String], obj : Option[String], offset : Int = 0, limit : Int = 20) : (Boolean,Seq[Triple])
  def search(query : String, property : Option[String], limit : Int = 20) : List[String]
  def load(inputStream : java.io.InputStream, ignoreErrors : Boolean) : Unit
  def close() : Unit
}


// Standard SQL Implementation
class RDFBackend(db : String) extends Backend {
  try {
    Class.forName("org.sqlite.JDBC")
  } catch {
    case x : ClassNotFoundException => throw new RuntimeException("No SQLite Driver", x)
  }

  private val conn = DriverManager.getConnection("jdbc:sqlite:" + db)

  def close() {
    if(!conn.isClosed()) {
      conn.close()
    }
  }

  def graph = new RDFBackendGraph(this)
 
  def from_n3(n3 : String, model : Model) = if(n3.startsWith("<") && n3.endsWith(">")) {
    model.createResource(n3.drop(1).dropRight(1))
  } else if(n3.startsWith("_:")) {
    model.createResource(AnonId.create(n3.drop(2)))
  } else if(n3.startsWith("\"") && n3.contains("^^")) {
    val Array(lit,typ) = n3.split("\"\\^\\^",2)
    model.createTypedLiteral(lit.drop(1), NodeFactory.getType(typ.drop(1).dropRight(1)))
  } else if(n3.startsWith("\"") && n3.contains("\"@")) {
    val Array(lit,lang) = n3.split("\"@", 2)
    model.createLiteral(lit.drop(1), lang)
  } else if(n3.startsWith("\"") && n3.endsWith("\"")) {
    model.createLiteral(n3.drop(1).dropRight(1))
  } else {
    throw new IllegalArgumentException("Not N3: %s" format n3)
  }

  def make_prop(uri : String, model : Model) = if(uri.contains('#')) {
    val (prop,frag) = uri.splitAt(uri.indexOf('#')+1)
    model.createProperty(prop,frag)
  } else if(uri.contains("/")) {
    val (prop,frag) = uri.splitAt(uri.lastIndexOf('/'))
    model.createProperty(prop,frag)
  } else {
    model.createProperty(uri,"")
  }

  def prop_from_n3(n3 : String, model : Model) =  if(n3.startsWith("<") && n3.endsWith(">")) {
    make_prop(n3.drop(1).dropRight(1), model)
  } else {
    throw new IllegalArgumentException("Not N3: %s" format n3)
  }

  def lookup(id : String) : Option[Model] = {
    val ps = conn.prepareStatement("select fragment, property, object, inverse from triples where subject=?")
    ps.setString(1,id)
    val rows = ps.executeQuery()
    if(!rows.next()) {
      return None
    }
    val model = ModelFactory.createDefaultModel()
    do {
      val f = rows.getString(1)
      val p = rows.getString(2)
      val o = rows.getString(3)
      val i = rows.getInt(4)
      if(i != 0) {
        val subject = from_n3(o, model)
        val property = prop_from_n3(p, model)
        val obj = model.getRDFNode(RDFBackend.name(id, Option(f)))
        println(subject)
        subject match {
          case r : Resource => r.addProperty(property, obj)
          case _ => {println(subject)}
        }
      } else {
        val subject = model.getRDFNode(RDFBackend.name(id, Option(f)))
        val property = prop_from_n3(p, model)
        val obj = from_n3(o, model)
        subject match {
          case r : Resource => r.addProperty(property, obj)
        }
        if(o.startsWith("_:")) {
          lookupBlanks(model, obj.asInstanceOf[Resource])
        }
      }
    } while(rows.next())
    ps.close()
    return Some(model)
  }

  def lookupBlanks(model : Model, bn : Resource) {
    val ps = conn.prepareStatement("select property, object from triples where subject=\"<BLANK>\" and fragment=?")
    ps.setString(1, bn.getId().getLabelString())
    val rows = ps.executeQuery()
    while(rows.next()) {
      val p = rows.getString(1)
      val o = rows.getString(2)
      val property = prop_from_n3(p, model)
      val obj = from_n3(o, model)
      bn.addProperty(property, obj)
      if(o.startsWith("_:")) {
        lookupBlanks(model, obj.asInstanceOf[Resource])
      }
    }
    ps.close()
  }


  def search(query : String, property : Option[String], limit : Int = 20) : List[String] = {
    val ps = property match {
      case Some(p) => {
        val ps2 = if(EXACT_QUERY) {
          conn.prepareStatement("select distinct subject from triples where property=? and object=? limit ?")
        } else {
          conn.prepareStatement("select distinct subject from triples where property=? and object like ? limit ?")
        }
        ps2.setString(1, "<%s>" format p)
        if(EXACT_QUERY) {
          ps2.setString(2, "\"%s\"" format query)
        } else {
          ps2.setString(2, "%%%s%%" format query)
        }
        ps2.setInt(3, limit)
        ps2
      } 
      case None => {
        val ps2 = if(EXACT_QUERY) {
          conn.prepareStatement("select distinct subject from triples where object=? limit ?")
        } else {
          conn.prepareStatement("select distinct subject from triples where object like ? limit ?")
        }
        if(EXACT_QUERY) {
          ps2.setString(1, "\"%s\"" format query)
        } else {
          ps2.setString(1, "%%%s%%" format query)
        }
        ps2.setInt(2, limit)
        ps2
      }
    }
    val results = collection.mutable.ListBuffer[String]()
    val rs = ps.executeQuery()
    while(rs.next()) {
      results += rs.getString(1)
    }
    ps.close
    return results.toList
  }

  private[yuzu] def listInternal(subj : Option[String], frag : Option[String], prop : Option[String], obj : Option[String], offset : Int = 0) : (ResultSet, PreparedStatement) = {
    var ps : PreparedStatement = null
    val rs : ResultSet = subj match {
      case None => prop match {
        case None => obj match {
          case None => 
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where inverse=0")
            ps.executeQuery()
          case Some(o) =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where object=? and inverse=0")
            ps.setString(1, o)
            ps.executeQuery()  
        }
        case Some(p) => obj match {
          case None =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where property=? and inverse=0")
            ps.setString(1, p)
            ps.executeQuery()
          case Some(o) =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where property=? and inverse=0 and object=?")
            ps.setString(1, p)
            ps.setString(2, o)
            ps.executeQuery()
        }
      }
      case Some(id) => prop match {
        case None => obj match {
          case None =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where subject=? and fragment=? and inverse=0")
            ps.setString(1, id)
            ps.setString(2, frag.getOrElse(""))
            ps.executeQuery()
          case Some(o) =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where subject=? and fragment=? and object=? and inverse=0")
            ps.setString(1, id)
            ps.setString(2, frag.getOrElse(""))
            ps.setString(3, o)
            ps.executeQuery()
          }
        case Some(p) => obj match {
          case None =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where subject=? and fragment=? and property=? and inverse=0")
            ps.setString(1, id)
            ps.setString(2, frag.getOrElse(""))
            ps.setString(3, p)
            ps.executeQuery()
          case Some(o) =>
            ps = conn.prepareStatement("select subject, fragment, property, object from triples where subject=? and fragment=? and property=? and object=? and inverse=0")
            ps.setString(1, id)
            ps.setString(2, frag.getOrElse(""))
            ps.setString(3, p)
            ps.setString(4, o)
            ps.executeQuery()
          }
        }
      }
    return (rs,ps)
  }

  def list(subj : Option[String], prop : Option[String], obj : Option[String], 
    offset : Int = 0, limit : Int = 20) : (Boolean, Seq[Triple]) = {
    val (id,frag) = subj match {
      case Some(s) => RDFBackend.unname(s) match {
        case Some((i,f)) => (Some(i),f)
        case None => return (false,Nil)
      }
      case None => (None,None)
    }
    val (rs, ps) = listInternal(id,frag,prop,obj,offset)
    val model = ModelFactory.createDefaultModel()
    try {
      var results = collection.mutable.ListBuffer[Triple]()
      while(results.size < limit && rs.next()) {
        val subject = RDFBackend.name(rs.getString(1), Some(rs.getString(2)))
        val property = from_n3(rs.getString(3),model).asNode()
        val obj = from_n3(rs.getString(4),model).asNode()
        results += new Triple(subject,property,obj)
      }
      return (rs.next(), results.toSeq)
    } finally {
      rs.close()
      ps.close()
    }
  }

   
  def listResources(offset : Int, limit : Int, prop : Option[String] = None, obj : Option[String] = None) : (Boolean,Seq[String]) = {
    val ps = try {
      prop match {
        case Some(p) => obj match {
          case Some(o) => 
            val ps = conn.prepareStatement("select distinct subject from triples where property=? and object=? limit ? offset ?")
            ps.setString(1,p)
            ps.setString(2,o)
            ps.setInt(3,limit+1)
            ps.setInt(4,offset)
            ps
          case None =>
            val ps = conn.prepareStatement("select distinct subject from triples where property=? limit ? offset ?")
            ps.setString(1,p)
            ps.setInt(2,limit+1)
            ps.setInt(3,offset)
            ps
        }
        case None =>
          val ps = conn.prepareStatement("select distinct subject from triples limit ? offset ?")
          ps.setInt(1, limit + 1)
          ps.setInt(2, offset)
          ps
      }
    } catch {
      case x : SQLException => throw new RuntimeException("Database @ " + db + " not initialized", x)
    }
   val rs = ps.executeQuery()
    var n = 0
    if(!rs.next()) {
      ps.close()
      return (false, Nil)
    }
    var results = collection.mutable.ListBuffer[String]()
    do {
      rs.getString(1) match {
        case "<BLANK>" => {}
        case result => results += result
      }
      n += 1
    } while(rs.next())
    ps.close()
    if(n >= limit) {
      results.remove(n - 1)
      return (true, results.toSeq)
    } else {
      return (false, results.toSeq)
    }
  }

  def listValues(offset : Int, limit : Int, prop : String) : (Boolean,Seq[String]) = {
    val ps = try {
      conn.prepareStatement("select distinct object from triples where property=? limit ? offset ?")
    } catch {
      case x : SQLException => throw new RuntimeException("Database @ " + db + " not initialized", x)
    }
    ps.setString(1,prop)
    ps.setInt(2,limit+1)
    ps.setInt(3,offset)
    val rs = ps.executeQuery()
    var n = 0
    if(!rs.next()) {
      ps.close()
      return (false, Nil)
    }
    var results = collection.mutable.ListBuffer[String]()
    do {
      results += rs.getString(1)
      n += 1
    } while(rs.next())
    ps.close()
    if(n >= limit) {
      results.remove(n - 1)
      return (true, results.toSeq)
    } else {
      return (false, results.toSeq)
    }
  }


  def load(inputStream : java.io.InputStream, ignoreErrors : Boolean) {
    def splitUri(subj : String) : (String, String) = {
      val (id2, frag) = if(subj contains '#') {
        (subj.slice(BASE_NAME.length, subj.indexOf('#')),
          subj.drop(subj.indexOf('#') + 1))
      } else {
        (subj.drop(BASE_NAME.length), "")
      }
      val id = if(id2.endsWith(".rdf") || id2.endsWith(".ttl") || id2.endsWith(".nt") || id2.endsWith(".json") || id2.endsWith(".xml")) {
        System.err.println("File type at end of name (%s) dropped\n" format id2)
        id2.take(id2.lastIndexOf("."))
      } else {
        id2
      }
      (id,frag)
    }
    val cursor = conn.createStatement()
    val oldAutocommit = conn.getAutoCommit()
    try {
      conn.setAutoCommit(false)
      cursor.execute("create table if not exists [triples] ([subject] TEXT, [fragment] TEXT, property TEXT NOT NULL, object TEXT NOT NULL, inverse INT DEFAULT 0)")
      cursor.execute("create index if not exists k_triples_subject ON [triples] ( subject )")
      if(SPARQL_ENDPOINT == None) {
        cursor.execute("create index if not exists k_triples_fragment ON [triples] ( fragment )")
        cursor.execute("create index if not exists k_triples_property ON [triples] ( property )")
        cursor.execute("create index if not exists k_triples_object ON [triples] ( object )")
      }
      var linesRead = 0
      var lineIterator = io.Source.fromInputStream(inputStream).getLines
      while(lineIterator.hasNext) {
        try {
          val line = lineIterator.next
          linesRead += 1
          if(linesRead % 100000 == 0) {
            System.err.print(".")
            System.err.flush()
            conn.commit()
          }
          val e = line.split(" ")
          val subj = e(0).drop(1).dropRight(1)
          if(subj.startsWith(BASE_NAME)) {
            val (id, frag) = splitUri(subj)
            val prop = e(1)
            val obj = e.drop(2).dropRight(1).mkString(" ")
            val ps1 = conn.prepareStatement("insert into triples values (?, ?, ?, ?, 0)")
            ps1.setString(1, RDFBackend.unicodeEscape(id))
            ps1.setString(2, RDFBackend.unicodeEscape(frag))
            ps1.setString(3, RDFBackend.unicodeEscape(prop))
            ps1.setString(4, RDFBackend.unicodeEscape(obj))
            ps1.execute()
            ps1.close()
            
            if(obj.startsWith("<"+BASE_NAME)) {
              val (id2, frag2) = splitUri(obj.drop(1).dropRight(1))
              val ps2 = conn.prepareStatement("insert into triples values (?, ?, ?, ?, 1)")
              ps2.setString(1, id2)
              ps2.setString(2, frag2)
              ps2.setString(3, prop)
              ps2.setString(4, "<"+subj+">")
              ps2.execute()
              ps2.close()
            }
          } else if(subj.startsWith("_:")) {
            val (id, frag) = ("<BLANK>", subj.drop(2))
            val prop = e(1)
            val obj = e.drop(2).dropRight(1).mkString(" ")
            val ps1 = conn.prepareStatement("insert into triples values (?, ?, ?, ?, 0)")
            ps1.setString(1, RDFBackend.unicodeEscape(id))
            ps1.setString(2, RDFBackend.unicodeEscape(frag))
            ps1.setString(3, RDFBackend.unicodeEscape(prop))
            ps1.setString(4, RDFBackend.unicodeEscape(obj))
            ps1.execute()
            ps1.close()
          }
        } catch {
          case t : Throwable =>
            System.err.println("Error on line %d: %s" format (linesRead, t.getMessage()))
            if(!ignoreErrors) {
              throw t
            }
        }

      }
      if(linesRead > 100000) {
        System.err.println()
      }
    } finally {
      cursor.close()
      conn.commit()
      conn.setAutoCommit(oldAutocommit)
    }
  }

  def query(query : String, mimeType : ResultType, defaultGraphURI : Option[String], timeout : Int = 10) : SPARQLResult = {
    val backendModel = ModelFactory.createModelForGraph(graph)
    val q = defaultGraphURI match {
      case Some(uri) => QueryFactory.create(query, uri)
      case None => QueryFactory.create(query)
    }
    val qx = SPARQL_ENDPOINT match {
      case Some(endpoint) => {
        QueryExecutionFactory.sparqlService(endpoint, q)
      }
      case None => {
        QueryExecutionFactory.create(q, backendModel)
      }
    }
    val ste = Executors.newSingleThreadExecutor()
    val executor = new SPARQLExecutor(q, qx)
    ste.submit(executor)
    ste.shutdown()
    ste.awaitTermination(timeout, TimeUnit.SECONDS)
    if(!ste.isTerminated()) {
      ste.shutdownNow()
      throw new TimeoutException()
    } else {
      return executor
    }
  }
}


object RDFBackend {

  def name(id : String, frag : Option[String]) = {
    if(id == "<BLANK>") {
      NodeFactory.createAnon(AnonId.create(frag.get))
    } else {
      NodeFactory.createURI(
        frag match {
          case Some("") => "%s%s" format (BASE_NAME, id)
          case Some(f) => "%s%s#%s" format (BASE_NAME, id, f)
          case None => "%s%s" format (BASE_NAME, id)
        }
      )
    }
  }

  def unname(uri : String) = if(uri.startsWith(BASE_NAME)) {
    if(uri contains '#') {
      val id = uri.slice(BASE_NAME.length, uri.indexOf('#'))
      val frag = uri.drop(uri.indexOf('#') + 1)
      Some((id, Some(frag)))
    } else {
      Some((uri.drop(BASE_NAME.length), None))
    }
  } else {
    None
  }


  def to_n3(node : Node) : String = if(node.isURI()) {
    return "<%s>" format node.getURI()
  } else if(node.isBlank()) {
    return "_:%s" format node.getBlankNodeId().toString()
  } else if(node.getLiteralLanguage() != "") {
    return "\"%s\"@%s" format (node.getLiteralValue().toString().replaceAll("\"","\\\\\""), node.getLiteralLanguage())
  } else if(node.getLiteralDatatypeURI() != null) {
    return "\"%s\"^^<%s>" format (node.getLiteralValue().toString().replaceAll("\"","\\\\\""), node.getLiteralDatatypeURI())
  } else {
    return "\"%s\"" format (node.getLiteralValue().toString().replaceAll("\"","\\\\\""))
  }

  def unicodeEscape(str : String) : String = {
    val sb = new StringBuilder(str)
    var i = 0
    while(i < sb.length) {
      if(sb.charAt(i) == '\\' && sb.charAt(i+1) == 'u') {
      //if(sb.slice(i,i+2).toString == "\\u") {
        sb.replace(i,i+6, Integer.parseInt(sb.slice(i+2,i+6).toString, 16).toChar.toString)
      }
      i += 1
    }
    return sb.toString
  }

  def main(args : Array[String]) {
    val getopt = new Getopt("yuzubackend", args, "d:f:e")
    var opts = collection.mutable.Map[String, String]()
    var c = 0
    while({c = getopt.getopt(); c } != -1) {
      c match {
        case 'd' => opts("-d") = getopt.getOptarg()
        case 'f' => opts("-f") = getopt.getOptarg()
        case 'e' => opts("-e") = "true"
      }
    }
    val backend = new RDFBackend(opts.getOrElse("-d", DB_FILE))
    val endsGZ = ".*\\.gz".r
    val inputStream = opts.getOrElse("-f", DUMP_FILE) match {
      case file @ endsGZ() => new GZIPInputStream(new FileInputStream(file))
      case file => new FileInputStream(file)
    }
    backend.load(inputStream, opts contains "-e")
    backend.close()
  }
}

