/*  Title:      Pure/Admin/isabelle_cronjob.scala
    Author:     Makarius

Main entry point for administrative cronjob at TUM.
*/

package isabelle


import java.nio.file.Files

import scala.annotation.tailrec


object Isabelle_Cronjob {
  /* global resources: owned by main cronjob */

  val backup = "lxbroy10:cronjob"
  val main_dir: Path = Path.explode("~/cronjob")
  val main_state_file: Path = main_dir + Path.explode("run/main.state")
  val build_release_log: Path = main_dir + Path.explode("run/build_release.log")
  val current_log: Path = main_dir + Path.explode("run/main.log")  // owned by log service
  val cumulative_log: Path = main_dir + Path.explode("log/main.log")  // owned by log service

  val isabelle_repos: Path = main_dir + Path.explode("isabelle")
  val afp_repos: Path = main_dir + Path.explode("AFP")

  val mailman_archives_dir = Path.explode("~/cronjob/Mailman")

  val build_log_dirs =
    List(Path.explode("~/log"), Path.explode("~/afp/log"), Path.explode("~/cronjob/log"))



  /** logger tasks **/

  sealed case class Logger_Task(name: String = "", body: Logger => Unit)


  /* init and exit */

  def get_rev(): String = Mercurial.repository(isabelle_repos).id()
  def get_afp_rev(): String = Mercurial.repository(afp_repos).id()

  val init: Logger_Task =
    Logger_Task("init",
      { logger =>
        Isabelle_Devel.make_index()

        Mercurial.setup_repository(Isabelle_System.afp_repository.root, afp_repos)

        File.write(logger.log_dir + Build_Log.log_filename("isabelle_identify", logger.start_date),
          Build_Log.Identify.content(logger.start_date, Some(get_rev()), Some(get_afp_rev())))

        Isabelle_System.bash(
          File.bash_path(Component_Rsync.local_program) +
            """ -a --include="*/" --include="plain_identify*" --exclude="*" """ +
            Bash.string(backup + "/log/.") + " " + File.bash_path(main_dir) + "/log/.").check

        if (!Isabelle_Devel.cronjob_log.is_file) {
          Files.createSymbolicLink(Isabelle_Devel.cronjob_log.java_path, current_log.java_path)
        }
      })

  val exit: Logger_Task =
    Logger_Task("exit",
      { logger =>
        Isabelle_System.bash(
          File.bash_path(Component_Rsync.local_program) +
            " -a " + File.bash_path(main_dir) + "/log/." + " " + Bash.string(backup) + "/log/.")
            .check
      })


  /* Mailman archives */

  val mailman_archives: Logger_Task =
    Logger_Task("mailman_archives",
      { logger =>
        Mailman.Isabelle_Users.download(mailman_archives_dir)
        Mailman.Isabelle_Dev.download(mailman_archives_dir)
      })


  /* build release */

  val build_release: Logger_Task =
    Logger_Task("build_release", { logger =>
      build_release_log.file.delete
      Isabelle_Devel.release_snapshot(logger.options, get_rev(), get_afp_rev(),
        progress = new File_Progress(build_release_log))
    })


  /* remote build_history */

  sealed case class Item(
    known: Boolean,
    isabelle_version: String,
    afp_version: Option[String],
    pull_date: Date
  ) {
    def unknown: Boolean = !known
    def versions: (String, Option[String]) = (isabelle_version, afp_version)

    def known_versions(rev: String, afp_rev: Option[String]): Boolean =
      known && rev != "" && isabelle_version == rev &&
      (afp_rev.isEmpty || afp_rev.get != "" && afp_version == afp_rev)
  }

  def recent_items(
    db: SQL.Database,
    days: Int,
    rev: String,
    afp_rev: Option[String],
    sql: PostgreSQL.Source
  ): List[Item] = {
    val afp = afp_rev.isDefined

    db.execute_query_statement(
      Build_Log.Data.select_recent_versions(
        days = days, rev = rev, afp_rev = afp_rev, sql = SQL.where(sql)),
      List.from[Item],
      { res =>
        val known = res.bool(Build_Log.Data.known)
        val isabelle_version = res.string(Build_Log.Prop.isabelle_version)
        val afp_version = if (afp) proper_string(res.string(Build_Log.Prop.afp_version)) else None
        val pull_date = res.date(Build_Log.Data.pull_date(afp))
        Item(known, isabelle_version, afp_version, pull_date)
      })
  }

  def unknown_runs(items: List[Item]): List[List[Item]] = {
    val (run, rest) = Library.take_prefix[Item](_.unknown, items.dropWhile(_.known))
    if (run.nonEmpty) run :: unknown_runs(rest) else Nil
  }

  sealed case class Remote_Build(
    description: String,
    host: String,
    user: String = "",
    port: Int = 0,
    historic: Boolean = false,
    history: Int = 0,
    history_base: String = "build_history_base",
    components_base: String = Components.dynamic_components_base,
    clean_components: Boolean = true,
    java_heap: String = "",
    options: String = "",
    args: String = "",
    afp: Boolean = false,
    bulky: Boolean = false,
    more_hosts: List[String] = Nil,
    detect: PostgreSQL.Source = "",
    active: () => Boolean = () => true
  ) {
    def open_session(options: Options): SSH.Session =
      SSH.open_session(options, host = host, user = user, port = port)

    def sql: PostgreSQL.Source =
      SQL.and(
        Build_Log.Prop.build_engine.equal(Build_History.engine),
        Build_Log.Prop.build_host.member(host :: more_hosts),
        if_proper(detect, SQL.enclose(detect)))

    def profile: Build_Status.Profile =
      Build_Status.Profile(description, history = history, afp = afp, bulky = bulky, sql = sql)

    def pick(
      options: Options,
      rev: String = "",
      filter: Item => Boolean = _ => true
    ): Option[(String, Option[String])] = {
      val afp_rev = if (afp) Some(get_afp_rev()) else None

      val store = Build_Log.store(options)
      using(store.open_database()) { db =>
        def pick_days(days: Int, gap: Int): Option[(String, Option[String])] = {
          val items = recent_items(db, days, rev, afp_rev, sql).filter(filter)
          def runs = unknown_runs(items).filter(run => run.length >= gap)

          if (historic || items.exists(_.known_versions(rev, afp_rev))) {
            val longest_run =
              runs.foldLeft(List.empty[Item]) {
                case (item1, item2) => if (item1.length >= item2.length) item1 else item2
              }
            if (longest_run.isEmpty) None
            else Some(longest_run(longest_run.length / 2).versions)
          }
          else if (rev != "") Some((rev, afp_rev))
          else runs.flatten.headOption.map(_.versions)
        }

        pick_days(options.int("build_log_history") max history, 2) orElse
        pick_days(200, 5) orElse
        pick_days(2000, 1)
      }
    }

    def build_history_options: String =
      " -h " + Bash.string(host) + " " +
      if_proper(java_heap,
        "-e 'ISABELLE_TOOL_JAVA_OPTIONS=\"$ISABELLE_TOOL_JAVA_OPTIONS -Xmx" + java_heap + "\"' ") +
      options
  }

  val remote_builds_old: List[Remote_Build] =
    List(
      Remote_Build("macOS 10.15 Catalina", "laramac01", user = "makarius",
        options = "-m32 -M4 -e ISABELLE_GHC_SETUP=true -p pide_session=false",
        args = "-a -d '~~/src/Benchmarks'"),
      Remote_Build("Linux A", "i21of4", user = "i21isatest",
        options = "-m32 -M1x4,2,4" +
          " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
          " -e ISABELLE_GHC_SETUP=true" +
          " -e ISABELLE_MLTON=mlton" +
          " -e ISABELLE_SMLNJ=sml" +
          " -e ISABELLE_SWIPL=swipl",
        args = "-a -d '~~/src/Benchmarks'"),
      Remote_Build("Linux A", "lxbroy9",
        java_heap = "2g", options = "-m32 -B -M1x2,2", args = "-N -g timing"),
      Remote_Build("Linux Benchmarks", "lxbroy5", historic = true, history = 90,
        java_heap = "2g",
        options = "-m32 -B -M1x2,2 -t Benchmarks" +
          " -e ISABELLE_GHC=ghc -e ISABELLE_MLTON=mlton -e ISABELLE_OCAML=ocaml" +
          " -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAMLFIND=ocamlfind -e ISABELLE_SMLNJ=sml" +
          " -e ISABELLE_SWIPL=swipl",
        args = "-N -a -d '~~/src/Benchmarks'",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("Benchmarks")),
      Remote_Build("macOS 10.14 Mojave (Old)", "lapnipkow3",
        options = "-m32 -M1,2 -e ISABELLE_GHC_SETUP=true -p pide_session=false",
        args = "-a -d '~~/src/Benchmarks'"),
      Remote_Build("AFP old bulky", "lrzcloud1",
        options = "-m64 -M6 -U30000 -s10 -t AFP",
        args = "-g large -g slow",
        afp = true,
        bulky = true,
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("AFP")),
      Remote_Build("AFP old", "lxbroy7",
          args = "-N -X large -X slow",
          afp = true,
          detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("AFP")),
      Remote_Build("Poly/ML 5.7 Linux", "lxbroy8",
        history_base = "37074e22e8be",
        options = "-m32 -B -M1x2,2 -t polyml-5.7 -i 'init_component /home/isabelle/contrib/polyml-5.7'",
        args = "-N -g timing",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("polyml-5.7") + " AND " +
          Build_Log.Settings.ML_OPTIONS + " <> " + SQL.string("-H 500")),
      Remote_Build("Poly/ML 5.7.1 Linux", "lxbroy8",
        history_base = "a9d5b59c3e12",
        options = "-m32 -B -M1x2,2 -t polyml-5.7.1-pre2 -i 'init_component /home/isabelle/contrib/polyml-test-905dae2ebfda'",
        args = "-N -g timing",
        detect =
          Build_Log.Prop.build_tags.toString + " = " + SQL.string("polyml-5.7.1-pre1") + " OR " +
          Build_Log.Prop.build_tags + " = " + SQL.string("polyml-5.7.1-pre2")),
      Remote_Build("Poly/ML 5.7 macOS", "macbroy2",
        history_base = "37074e22e8be",
        options = "-m32 -B -M1x4,4 -t polyml-5.7 -i 'init_component /home/isabelle/contrib/polyml-5.7'",
        args = "-a",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("polyml-5.7")),
      Remote_Build("Poly/ML 5.7.1 macOS", "macbroy2",
        history_base = "a9d5b59c3e12",
        options = "-m32 -B -M1x4,4 -t polyml-5.7.1-pre2 -i 'init_component /home/isabelle/contrib/polyml-test-905dae2ebfda'",
        args = "-a",
        detect =
          Build_Log.Prop.build_tags.toString + " = " + SQL.string("polyml-5.7.1-pre1") + " OR " +
          Build_Log.Prop.build_tags + " = " + SQL.string("polyml-5.7.1-pre2")),
      Remote_Build("macOS", "macbroy2",
        options = "-m32 -M8" +
          " -e ISABELLE_GHC=ghc -e ISABELLE_MLTON=mlton -e ISABELLE_OCAML=ocaml" +
          " -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
          " -e ISABELLE_OPAM_ROOT=\"$ISABELLE_HOME/opam\"" +
          " -e ISABELLE_SMLNJ=/home/isabelle/smlnj/110.85/bin/sml" +
          " -p pide_session=false",
        args = "-a",
        detect = Build_Log.Prop.build_tags.undefined,
        history_base = "2c0f24e927dd"),
      Remote_Build("macOS, quick_and_dirty", "macbroy2",
        options = "-m32 -M8 -t quick_and_dirty -p pide_session=false", args = "-a -o quick_and_dirty",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("quick_and_dirty"),
        history_base = "2c0f24e927dd"),
      Remote_Build("macOS, skip_proofs", "macbroy2",
        options = "-m32 -M8 -t skip_proofs -p pide_session=false", args = "-a -o skip_proofs",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("skip_proofs"),
        history_base = "2c0f24e927dd"),
      Remote_Build("Poly/ML test", "lxbroy8",
        options = "-m32 -B -M1x2,2 -t polyml-test -i 'init_component /home/isabelle/contrib/polyml-5.7-20170217'",
        args = "-N -g timing",
        detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("polyml-test")),
      Remote_Build("macOS 10.12 Sierra", "macbroy30", options = "-m32 -M2 -p pide_session=false", args = "-a",
        detect = Build_Log.Prop.build_start.toString + " > date '2017-03-03'"),
      Remote_Build("macOS 10.10 Yosemite", "macbroy31", options = "-m32 -M2 -p pide_session=false", args = "-a"),
      Remote_Build("macOS 10.8 Mountain Lion", "macbroy30", options = "-m32 -M2", args = "-a",
        detect = Build_Log.Prop.build_start.toString + " < date '2017-03-03'")) :::
      {
        for ((n, hosts) <- List(1 -> List("lxbroy6"), 2 -> List("lxbroy8", "lxbroy5")))
        yield {
          Remote_Build("AFP old", host = hosts.head, more_hosts = hosts.tail,
            options = "-m32 -M1x2 -t AFP -P" + n +
              " -e ISABELLE_GHC=ghc" +
              " -e ISABELLE_MLTON=mlton" +
              " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAMLFIND=ocamlfind" +
              " -e ISABELLE_SMLNJ=sml",
            args = "-N -X large -X slow",
            afp = true,
            detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("AFP"))
        }
      }

  val remote_builds1: List[List[Remote_Build]] = {
    List(
      List(Remote_Build("Linux A", "augsburg1",
          options = "-m32 -B -M4" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAMLFIND=ocamlfind" +
            " -e ISABELLE_GHC_SETUP=true" +
            " -e ISABELLE_MLTON=mlton -e ISABELLE_MLTON_OPTIONS=" +
            " -e ISABELLE_SMLNJ=sml" +
            " -e ISABELLE_SWIPL=swipl",
          args = "-a -d '~~/src/Benchmarks'")),
      List(Remote_Build("Linux B", "lxbroy10", historic = true, history = 90,
        options = "-m32 -B -M1x4,2,4,6", args = "-N -g timing")),
      List(Remote_Build("macOS 10.13 High Sierra", "lapbroy68",
        options = "-m32 -B -M1,2,4 -e ISABELLE_GHC_SETUP=true -p pide_session=false",
        args = "-a -d '~~/src/Benchmarks'")),
      List(
        Remote_Build("macOS 11 Big Sur", "mini1",
          options = "-m32 -B -M1x2,2,4 -p pide_session=false" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
            " -e ISABELLE_GHC_SETUP=true" +
            " -e ISABELLE_MLTON=/usr/local/bin/mlton -e ISABELLE_MLTON_OPTIONS=" +
            " -e ISABELLE_SMLNJ=/usr/local/smlnj/bin/sml" +
            " -e ISABELLE_SWIPL=/usr/local/bin/swipl",
          args = "-a -d '~~/src/Benchmarks'")),
      List(
        Remote_Build("macOS 10.14 Mojave", "mini2",
          options = "-m32 -B -M1x2,2,4 -p pide_session=false" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
            " -e ISABELLE_GHC_SETUP=true" +
            " -e ISABELLE_MLTON=/usr/local/bin/mlton -e ISABELLE_MLTON_OPTIONS=" +
            " -e ISABELLE_SMLNJ=/usr/local/smlnj/bin/sml" +
            " -e ISABELLE_SWIPL=/usr/local/bin/swipl",
          args = "-a -d '~~/src/Benchmarks'"),
        Remote_Build("macOS, quick_and_dirty", "mini2",
          options = "-m32 -M4 -t quick_and_dirty -p pide_session=false",
          args = "-a -o quick_and_dirty",
          detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("quick_and_dirty")),
        Remote_Build("macOS, skip_proofs", "mini2",
          options = "-m32 -M4 -t skip_proofs -p pide_session=false", args = "-a -o skip_proofs",
          detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("skip_proofs"))),
      List(
        Remote_Build("macOS 12 Monterey", "monterey", user = "makarius",
          options = "-m32 -M4 -e ISABELLE_GHC_SETUP=true -p pide_session=false",
          args = "-a -d '~~/src/Benchmarks'")),
      List(
        Remote_Build("Windows", "vmnipkow9", historic = true, history = 90,
          components_base = "/cygdrive/d/isatest/contrib",
          options = "-m32 -M4" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
            " -e ISABELLE_GHC_SETUP=true" +
            " -e ISABELLE_SMLNJ=/usr/local/smlnj-110.81/bin/sml",
          args = "-a",
          detect =
            Build_Log.Settings.ML_PLATFORM.toString + " = " + SQL.string("x86-windows") + " OR " +
            Build_Log.Settings.ML_PLATFORM + " = " + SQL.string("x86_64_32-windows")),
        Remote_Build("Windows", "vmnipkow9", historic = true, history = 90,
          components_base = "/cygdrive/d/isatest/contrib",
          options = "-m64 -M4" +
            " -C /cygdrive/d/isatest/contrib" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAML_SETUP=true" +
            " -e ISABELLE_GHC_SETUP=true" +
            " -e ISABELLE_SMLNJ=/usr/local/smlnj-110.81/bin/sml",
          args = "-a",
          detect = Build_Log.Settings.ML_PLATFORM.toString + " = " + SQL.string("x86_64-windows"))))
  }

  val remote_builds2: List[List[Remote_Build]] =
    List(
      List(
        Remote_Build("AFP", "lrzcloud2",
          java_heap = "8g",
          options = "-m32 -M1x5 -t AFP" +
            " -e ISABELLE_GHC=ghc" +
            " -e ISABELLE_MLTON=mlton -e ISABELLE_MLTON_OPTIONS=" +
            " -e ISABELLE_OCAML=ocaml -e ISABELLE_OCAMLC=ocamlc -e ISABELLE_OCAMLFIND=ocamlfind" +
            " -e ISABELLE_SMLNJ=sml",
          args = "-a -X large -X slow",
          afp = true,
          detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("AFP"),
          active = () => Date.now().unix_epoch_day % 2 == 0),
        Remote_Build("AFP", "lrzcloud2",
          java_heap = "8g",
          options = "-m64 -M8 -U30000 -s10 -t AFP",
          args = "-g large -g slow",
          afp = true,
          bulky = true,
          detect = Build_Log.Prop.build_tags.toString + " = " + SQL.string("AFP"),
          active = () => Date.now().unix_epoch_day % 2 == 1)))

  def remote_build_history(
    rev: String,
    afp_rev: Option[String],
    i: Int,
    r: Remote_Build
  ) : Logger_Task = {
    val task_name = "build_history-" + r.host
    Logger_Task(task_name,
      { logger =>
        using(r.open_session(logger.options)) { ssh =>
          val results =
            Build_History.remote_build(ssh,
              isabelle_repos,
              isabelle_repos.ext(r.host),
              isabelle_identifier = "cronjob_build_history",
              components_base = r.components_base,
              clean_platform = r.clean_components,
              clean_archives = r.clean_components,
              rev = rev,
              afp_repos = if (afp_rev.isDefined) Some(afp_repos) else None,
              afp_rev = afp_rev.getOrElse(""),
              options =
                " -N " + Bash.string(task_name) + (if (i < 0) "" else "_" + (i + 1).toString) +
                " -f " + r.build_history_options,
              args = "-o timeout=10800 " + r.args)

          for ((log_name, bytes) <- results) {
            logger.log(Date.now(), log_name)
            Bytes.write(logger.log_dir + Path.explode(log_name), bytes)
          }
        }
      })
  }

  val build_status_profiles: List[Build_Status.Profile] =
    (remote_builds_old :: remote_builds1 ::: remote_builds2).flatten.map(_.profile)



  /** task logging **/

  object Log_Service {
    def apply(options: Options, progress: Progress = new Progress): Log_Service =
      new Log_Service(options, progress)
  }

  class Log_Service private(val options: Options, progress: Progress) {
    current_log.file.delete

    private val thread: Consumer_Thread[String] =
      Consumer_Thread.fork("cronjob: logger", daemon = true)(
        consume =
          { (text: String) =>
            // critical
            File.append(current_log, text + "\n")
            File.append(cumulative_log, text + "\n")
            progress.echo(text)
            true
          })

    def shutdown(): Unit = { thread.shutdown() }

    val hostname: String = Isabelle_System.hostname()

    def log(date: Date, task_name: String, msg: String): Unit =
      if (task_name != "") {
        thread.send(
          "[" + Build_Log.print_date(date) + ", " + hostname + ", " + task_name + "]: " + msg)
      }

    def start_logger(start_date: Date, task_name: String): Logger =
      new Logger(this, start_date, task_name)

    def run_task(start_date: Date, task: Logger_Task): Unit = {
      val logger = start_logger(start_date, task.name)
      val res = Exn.capture { task.body(logger) }
      val end_date = Date.now()
      val err =
        res match {
          case Exn.Res(_) => None
          case Exn.Exn(exn) =>
            Output.writeln("Exception trace for " + quote(task.name) + ":")
            exn.printStackTrace()
            val first_line = split_lines(Exn.message(exn)).headOption getOrElse "exception"
            Some(first_line)
        }
      logger.log_end(end_date, err)
    }

    def fork_task(start_date: Date, task: Logger_Task): Task =
      new Task(task.name, run_task(start_date, task))
  }

  class Logger private[Isabelle_Cronjob](
    log_service: Log_Service,
    val start_date: Date,
    val task_name: String
  ) {
    def options: Options = log_service.options

    def log(date: Date, msg: String): Unit = log_service.log(date, task_name, msg)

    def log_end(end_date: Date, err: Option[String]): Unit = {
      val elapsed_time = end_date.time - start_date.time
      val msg =
        (if (err.isEmpty) "finished" else "ERROR " + err.get) +
        (if (elapsed_time.seconds < 3.0) "" else " (" + elapsed_time.message_hms + " elapsed time)")
      log(end_date, msg)
    }

    val log_dir = Isabelle_System.make_directory(main_dir + Build_Log.log_subdir(start_date))

    log(start_date, "started")
  }

  class Task private[Isabelle_Cronjob](name: String, body: => Unit) {
    private val future: Future[Unit] = Future.thread("cronjob: " + name) { body }
    def is_finished: Boolean = future.is_finished
  }



  /** cronjob **/

  def cronjob(progress: Progress, exclude_task: Set[String]): Unit = {
    /* soft lock */

    val still_running =
      try { Some(File.read(main_state_file)) }
      catch { case ERROR(_) => None }

    still_running match {
      case None | Some("") =>
      case Some(running) =>
        error("Isabelle cronjob appears to be still running: " + running)
    }


    /* log service */

    val log_service = Log_Service(Options.init(), progress = progress)

    def run(start_date: Date, task: Logger_Task): Unit = log_service.run_task(start_date, task)

    def run_now(task: Logger_Task): Unit = run(Date.now(), task)


    /* structured tasks */

    def SEQ(tasks: List[Logger_Task]): Logger_Task = Logger_Task(body = _ =>
      for (task <- tasks.iterator if !exclude_task(task.name) || task.name == "") run_now(task))

    def PAR(tasks: List[Logger_Task]): Logger_Task =
      Logger_Task(body =
        { _ =>
          @tailrec def join(running: List[Task]): Unit = {
            running.partition(_.is_finished) match {
              case (Nil, Nil) =>
              case (Nil, _ :: _) => Time.seconds(0.5).sleep(); join(running)
              case (_ :: _, remaining) => join(remaining)
            }
          }
          val start_date = Date.now()
          val running =
            for (task <- tasks if !exclude_task(task.name))
              yield log_service.fork_task(start_date, task)
          join(running)
        })


    /* repository structure */

    val hg = Mercurial.repository(isabelle_repos)
    val hg_graph = hg.graph()

    def history_base_filter(r: Remote_Build): Item => Boolean = {
      val base_rev = hg.id(r.history_base)
      val nodes = hg_graph.all_succs(List(base_rev)).toSet
      (item: Item) => nodes(item.isabelle_version)
    }


    /* main */

    val main_start_date = Date.now()
    File.write(main_state_file, main_start_date.toString + " " + log_service.hostname)

    run(main_start_date,
      Logger_Task("isabelle_cronjob", logger =>
        run_now(
          SEQ(List(
            init,
            PAR(List(mailman_archives, build_release)),
            PAR(
              List(remote_builds1, remote_builds2).map(remote_builds =>
              SEQ(List(
                PAR(remote_builds.map(_.filter(_.active())).map(seq =>
                  SEQ(
                    for {
                      (r, i) <- (if (seq.length <= 1) seq.map((_, -1)) else seq.zipWithIndex)
                      (rev, afp_rev) <- r.pick(logger.options, hg.id(), history_base_filter(r))
                    } yield remote_build_history(rev, afp_rev, i, r)))),
                Logger_Task("build_log_database",
                  logger =>
                    Build_Log.build_log_database(logger.options, build_log_dirs,
                      vacuum = true, ml_statistics = true,
                      snapshot = Some(Isabelle_Devel.build_log_snapshot))),
                Logger_Task("build_status",
                  logger => Isabelle_Devel.build_status(logger.options)))))),
            exit)))))

    log_service.shutdown()

    main_state_file.file.delete
  }



  /** command line entry point **/

  def main(args: Array[String]): Unit = {
    Command_Line.tool {
      var force = false
      var verbose = false
      var exclude_task = Set.empty[String]

      val getopts = Getopts("""
Usage: Admin/cronjob/main [OPTIONS]

  Options are:
    -f           apply force to do anything
    -v           verbose
    -x NAME      exclude tasks with this name
""",
        "f" -> (_ => force = true),
        "v" -> (_ => verbose = true),
        "x:" -> (arg => exclude_task += arg))

      val more_args = getopts(args)
      if (more_args.nonEmpty) getopts.usage()

      val progress = if (verbose) new Console_Progress() else new Progress

      if (force) cronjob(progress, exclude_task)
      else error("Need to apply force to do anything")
    }
  }
}
