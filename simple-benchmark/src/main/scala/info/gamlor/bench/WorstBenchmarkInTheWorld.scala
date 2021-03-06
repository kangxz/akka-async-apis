package info.gamlor.bench

import akka.util.duration._
import akka.dispatch.{Await, Future}
import com.typesafe.config.ConfigFactory
import util.Random
import akka.actor.{Props, Actor, ActorSystem}
import akka.routing.BroadcastRouter
import info.gamlor.db.{DatabaseAccess, DBConnection, Database}
import java.util.concurrent.atomic.AtomicLong

/**
 * @author roman.stoffel@gamlor.info
 */

object WorstBenchmarkInTheWorld extends App {
  private val AmountOfUsers = 200000

  implicit val akkaSystem = ActorSystem("benchmark", ConfigFactory.load().getConfig("benchmark"))

  main()


  def main() {

    //    val requestFullFiller = new NullFullfiller(akkaSystem)
//    val requestFullFiller = new WithPlainJDBC(akkaSystem)
    val requestFullFiller = new AysncDBApiFullfiller(akkaSystem)
//    val setupFuture = setup(Database(akkaSystem))
//
//    Await.result(setupFuture, 20 minutes)

    val testQuery = Database(akkaSystem).withConnection{
      conn=>
        conn.executeQuery("SELECT * FROM `user` LIMIT 0,1")
    }
    val rs = Await.result(testQuery, 5 minutes)
    println("Test me"+rs(0)(0).getString)
    println("Setup Done")

    connectTimeMeasuring(Database(akkaSystem))




//    runBenchmark(requestFullFiller)
//
//    Thread.sleep(30000)
//    Thread.sleep(10000)

    println(">>>>>>>>>>>>>>>>>Done?>>>>>>>>>")
    Thread.sleep(1000)
    System.exit(0)


  }

  def connectTimeMeasuring(dbAccess:DatabaseAccess){
    val avg = new AtomicLong()
    val sucesses = new AtomicLong()
    for (i<-0 to 2000){
      val startTime = System.currentTimeMillis()
      val testQuery = Database(akkaSystem).withConnection{
        conn=>
          conn.executeQuery("SELECT * FROM `user` LIMIT 0,1")
      }
      testQuery onSuccess {
        case data =>{
          val usedTime=(System.currentTimeMillis()-startTime);
          println("Select 1 in: "+usedTime)
          avg.addAndGet(usedTime)
          sucesses.incrementAndGet()
        }
      }
      if (i%20==1){
        Thread.sleep(20)
      }
    }

    Thread.sleep(2000)
    println("AVG time: "+(avg.get()/sucesses.get))

  }

  def setup(dbAccess:DatabaseAccess): Future[Unit] = {

    dbAccess.withConnection {
      conn => {
        for {
          _ <- createSchema(conn)
          _ <- insertUsersAndPosts(conn)
        } yield ()
      }

    }
  }

  def runBenchmark(requestFullFiller:RequestFullfiller) {
    val loadCreators = akkaSystem.actorOf(Props(new RequestSender(requestFullFiller))
      .withRouter(BroadcastRouter(4)))


    loadCreators ! Start
  }


  private def createSchema(conn: DBConnection): Future[String] =
    for {
      _ <- conn.executeUpdate("DROP TABLE IF EXISTS messages;")
      _ <- conn.executeUpdate("DROP TABLE IF EXISTS user;")
      _ <- conn.executeUpdate( """CREATE TABLE IF NOT EXISTS `messages` (
                                       `id` int(11) NOT NULL AUTO_INCREMENT,
                                       `message` varchar(255) NOT NULL,
                                       `userId` int(11) NOT NULL,
                                       `retweetOf` int(11) NOT NULL,
                                       `postTS` bigint(20) NOT NULL ,
                                       PRIMARY KEY (`id`),
                                       KEY `userId` (`userId`),
                                       KEY `retweetOf` (`retweetOf`),
                                       KEY `postTS` (`postTS`)
                                     ) ENGINE=InnoDB;""")
      - <- conn.executeUpdate( """CREATE TABLE IF NOT EXISTS `user` (
                                       `id` int(11) NOT NULL AUTO_INCREMENT,
                                       `username` varchar(255) NOT NULL,
                                       PRIMARY KEY (`id`),
                                       KEY `username` (`username`)
                                     ) ENGINE=InnoDB; """)
    } yield "done"

  private def insertUsersAndPosts(connection: DBConnection): Future[Unit] = {
    val inserts = for {
      insertUser <- connection.prepareUpdate("INSERT INTO user(username) VALUES (?)")
      insertPost <- connection.prepareUpdate("INSERT INTO messages(message,userId,retweetOf,postTS) VALUES (?,?,?,?)")
      insertedUsers <- Future.sequence(
        for (i <- 0 to AmountOfUsers)
        yield insertUser.execute("UserNo " + i)
      )
      initialPost <- Future.sequence(
        for (user <- insertedUsers)
        yield insertPost.execute("#Hello I'm new here. Ths is like #Twitter",
          user.generatedKeys(0, 0).getInt,0,
          System.currentTimeMillis()))
      secondPost <- Future.sequence(
        for (user <- insertedUsers)
        yield insertPost.execute("#Love the number #" + user.generatedKeys(0, 0) + " the most",
          user.generatedKeys(0, 0).getInt,0,
          System.currentTimeMillis()))
      thirdPost <- Future.sequence(
        for (user <- insertedUsers)
        yield {
          val rtOf = (Math.random * AmountOfUsers).asInstanceOf[Long]
          insertPost.execute("RT of some other tweet no: "+rtOf,
            user.generatedKeys(0, 0).getInt,rtOf,
            System.currentTimeMillis())
        })
      lastEntry <- Future.sequence(
        for (user <- insertedUsers)
        yield {
          val rtOf = (Math.random * AmountOfUsers*2).asInstanceOf[Long]
          insertPost.execute("Last RT of Mine: "+rtOf,
            user.generatedKeys(0, 0).getInt,rtOf,
            System.currentTimeMillis())
        })

    } yield lastEntry

    inserts.map(r => ())
  }


  class RequestSender(tweetSystem: RequestFullfiller) extends Actor {
    private var amountOfRounds = 30
    private val rnd = new Random()

    override def preStart() {
    }

    protected def receive = {
      case NewRequest => {
        val user = pickSomeUsers()
        new Request(user, tweetSystem).run()
        amountOfRounds = amountOfRounds - 1

        if (amountOfRounds > 0) {
          context.system.scheduler.scheduleOnce(200 + rnd.nextInt(800) millis, self, NewRequest)
        } else {
          context.stop(self)
        }
      }
      case Start => {
        context.system.scheduler.scheduleOnce(rnd.nextInt(1000) millis, self, NewRequest)
      }

    }

    private def pickSomeUsers() = {
      "UserNo " + rnd.nextInt(AmountOfUsers)
    }


    case object NewRequest

  }

  class Request(userName: String, tweetSystem: RequestFullfiller) {
    val startTime = System.currentTimeMillis()
    val rnd = new Random()


    def run() {
      val tweetsAndRelated = tweetSystem.requestTweets(userName)

      tweetsAndRelated.tweets.onSuccess{
        case _ => println("Tweets: " + (System.currentTimeMillis()-startTime))
      } onFailure{
        case x => {
          x.printStackTrace()
        }
      }
//      tweetsAndRelated.relatedTweets.onSuccess{
//        case _ => println("Related: " + (System.currentTimeMillis()-startTime))
//      }
//      tweetsAndRelated.amountOfTweets.onSuccess{
//        case _ => println("Stats: " + (System.currentTimeMillis()-startTime))
//      }

      val retweetDone = for {
        tweets <- tweetsAndRelated.tweets
        retweet <- tweetSystem.requestRetweet(tweets(rnd.nextInt(tweets.length)), "UserNo " + rnd.nextInt(AmountOfUsers))
      } yield retweet


//      retweetDone.onSuccess{
//        case _ => println("Retweet: " + (System.currentTimeMillis()-startTime))
//      }


      val allDone = for {
        _ <- tweetsAndRelated.tweets
        _ <- tweetsAndRelated.relatedTweets
        _ <- tweetsAndRelated.amountOfTweets
        rt <- retweetDone
      } yield ()

      allDone.onSuccess {
        case _ => {
          println("ALL DONE " + (System.currentTimeMillis() - startTime))
        }
      }

    }


  }


}


case object Start


