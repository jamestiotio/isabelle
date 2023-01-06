/*  Title:      Pure/Tools/update.scala
    Author:     Makarius

Update theory sources based on PIDE markup.
*/

package isabelle


object Update {
  def update(options: Options,
    selection: Sessions.Selection = Sessions.Selection.empty,
    base_logics: List[String] = Nil,
    progress: Progress = new Progress,
    build_heap: Boolean = false,
    clean_build: Boolean = false,
    dirs: List[Path] = Nil,
    select_dirs: List[Path] = Nil,
    numa_shuffling: Boolean = false,
    max_jobs: Int = 1,
    fresh_build: Boolean = false,
    no_build: Boolean = false,
    verbose: Boolean = false
  ): Build.Results = {
    /* excluded sessions */

    val exclude: Set[String] =
      if (base_logics.isEmpty) Set.empty
      else {
        val sessions =
          Sessions.load_structure(options, dirs = dirs, select_dirs = select_dirs)
            .selection(selection)

        for (name <- base_logics if !sessions.defined(name)) {
          error("Base logic " + quote(name) + " outside of session selection")
        }

        sessions.build_requirements(base_logics).toSet
      }


    /* build */

    val build_results =
      Build.build(options, progress = progress, dirs = dirs, select_dirs = select_dirs,
        selection = selection, numa_shuffling = numa_shuffling, max_jobs = max_jobs,
        no_build = no_build, verbose = verbose)

    val store = build_results.store
    val sessions_structure = build_results.deps.sessions_structure


    /* update */

    val path_cartouches = options.bool("update_path_cartouches")

    def update_xml(xml: XML.Body): XML.Body =
      xml flatMap {
        case XML.Wrapped_Elem(markup, body1, body2) =>
          if (markup.name == Markup.UPDATE) update_xml(body1) else update_xml(body2)
        case XML.Elem(Markup.Language.Path(_), body)
        if path_cartouches =>
          Token.read_embedded(Keyword.Keywords.empty, XML.content(body)) match {
            case Some(tok) => List(XML.Text(Symbol.cartouche(tok.content)))
            case None => update_xml(body)
          }
        case XML.Elem(_, body) => update_xml(body)
        case t => List(t)
      }

    var seen_theory = Set.empty[String]

    using(Export.open_database_context(store)) { database_context =>
      for {
        session <- sessions_structure.build_topological_order
        if build_results(session).ok && !exclude(session)
      } {
        progress.echo("Session " + session + " ...")
        using(database_context.open_session0(session)) { session_context =>
          for {
            db <- session_context.session_db()
            theory <- store.read_theories(db, session)
            if !seen_theory(theory)
          } {
            seen_theory += theory
            val theory_context = session_context.theory(theory)
            for {
              theory_snapshot <- Build_Job.read_theory(theory_context)
              node_name <- theory_snapshot.node_files
              snapshot = theory_snapshot.switch(node_name)
              if snapshot.node.source_wellformed
            } {
              progress.expose_interrupt()
              val source1 =
                XML.content(update_xml(
                  snapshot.xml_markup(elements = Markup.Elements(Markup.UPDATE, Markup.LANGUAGE))))
              if (source1 != snapshot.node.source) {
                val path = Path.explode(node_name.node)
                progress.echo("Updating " + quote(File.standard_path(path)))
                File.write(path, source1)
              }
            }
          }
        }
      }
    }

    build_results
  }


  /* Isabelle tool wrapper */

  val isabelle_tool =
    Isabelle_Tool("update", "update theory sources based on PIDE markup", Scala_Project.here,
      { args =>
        var base_sessions: List[String] = Nil
        var select_dirs: List[Path] = Nil
        var numa_shuffling = false
        var requirements = false
        var exclude_session_groups: List[String] = Nil
        var all_sessions = false
        var build_heap = false
        var clean_build = false
        var dirs: List[Path] = Nil
        var fresh_build = false
        var session_groups: List[String] = Nil
        var max_jobs = 1
        var base_logics: List[String] = Nil
        var no_build = false
        var options = Options.init()
        var verbose = false
        var exclude_sessions: List[String] = Nil

        val getopts = Getopts("""
Usage: isabelle update [OPTIONS] [SESSIONS ...]

  Options are:
    -B NAME      include session NAME and all descendants
    -D DIR       include session directory and select its sessions
    -R           refer to requirements of selected sessions
    -X NAME      exclude sessions from group NAME and all descendants
    -a           select all sessions
    -b           build heap images
    -c           clean build
    -d DIR       include session directory
    -f           fresh build
    -g NAME      select session group NAME
    -j INT       maximum number of parallel jobs (default 1)
    -l NAME      additional base logic
    -n           no build -- take existing build databases
    -o OPTION    override Isabelle system OPTION (via NAME=VAL or NAME)
    -u OPT       override "update" option: shortcut for "-o update_OPT"
    -v           verbose
    -x NAME      exclude session NAME and all descendants

  Update theory sources based on PIDE markup produced by "isabelle build".
""",
        "B:" -> (arg => base_sessions = base_sessions ::: List(arg)),
        "D:" -> (arg => select_dirs = select_dirs ::: List(Path.explode(arg))),
        "N" -> (_ => numa_shuffling = true),
        "R" -> (_ => requirements = true),
        "X:" -> (arg => exclude_session_groups = exclude_session_groups ::: List(arg)),
        "a" -> (_ => all_sessions = true),
        "b" -> (_ => build_heap = true),
        "c" -> (_ => clean_build = true),
        "d:" -> (arg => dirs = dirs ::: List(Path.explode(arg))),
        "f" -> (_ => fresh_build = true),
        "g:" -> (arg => session_groups = session_groups ::: List(arg)),
        "j:" -> (arg => max_jobs = Value.Int.parse(arg)),
        "l:" -> (arg => base_logics ::= arg),
        "n" -> (_ => no_build = true),
        "o:" -> (arg => options = options + arg),
        "u:" -> (arg => options = options + ("update_" + arg)),
        "v" -> (_ => verbose = true),
        "x:" -> (arg => exclude_sessions = exclude_sessions ::: List(arg)))

        val sessions = getopts(args)

        val progress = new Console_Progress(verbose = verbose)

        val results =
          progress.interrupt_handler {
            update(options,
              selection = Sessions.Selection(
                requirements = requirements,
                all_sessions = all_sessions,
                base_sessions = base_sessions,
                exclude_session_groups = exclude_session_groups,
                exclude_sessions = exclude_sessions,
                session_groups = session_groups,
                sessions = sessions),
              base_logics = base_logics,
              progress = progress,
              build_heap,
              clean_build,
              dirs = dirs,
              select_dirs = select_dirs,
              numa_shuffling = NUMA.enabled_warning(progress, numa_shuffling),
              max_jobs = max_jobs,
              fresh_build,
              no_build = no_build,
              verbose = verbose)
          }

        sys.exit(results.rc)
      })
}
