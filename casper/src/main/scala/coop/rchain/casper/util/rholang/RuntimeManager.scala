package coop.rchain.casper.util.rholang

import cats._
import cats.data.EitherT
import cats.effect.concurrent.MVar
import cats.effect.{Sync, _}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.CasperMetricsSource
import coop.rchain.casper.protocol._
import coop.rchain.casper.util.rholang.RuntimeManager.{evaluate, StateHash}
import coop.rchain.casper.util.rholang.SystemDeployFailure._
import coop.rchain.casper.util.{ConstructDeploy, EventConverter, ProtoUtil}
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Expr.ExprInstance.{EVarBody, GString}
import coop.rchain.models.NormalizerEnv.ToEnvMap
import coop.rchain.models.Validator.Validator
import coop.rchain.models.Var.VarInstance.FreeVar
import coop.rchain.models._
import coop.rchain.rholang.interpreter.Runtime.BlockData
import coop.rchain.rholang.interpreter.accounting._
import coop.rchain.rholang.interpreter.errors.BugFoundError
import coop.rchain.rholang.interpreter.{
  ErrorLog,
  EvaluateResult,
  Interpreter,
  Reduce,
  RhoType,
  Runtime
}
import coop.rchain.rspace.{Blake2b256Hash, ReplayException, Result}
import coop.rchain.shared.Log

trait RuntimeManager[F[_]] {
  def captureResults(
      startHash: StateHash,
      deploy: DeployData,
      name: String = "__SCALA__"
  ): F[Seq[Par]]
  def replayComputeState(startHash: StateHash)(
      terms: Seq[ProcessedDeploy],
      blockData: BlockData,
      invalidBlocks: Map[BlockHash, Validator],
      isGenesis: Boolean
  ): F[Either[ReplayFailure, StateHash]]
  def computeState(hash: StateHash)(
      terms: Seq[DeployData],
      blockData: BlockData,
      invalidBlocks: Map[BlockHash, Validator]
  ): F[(StateHash, Seq[ProcessedDeploy])]
  def computeGenesis(
      terms: Seq[DeployData],
      blockTime: Long
  ): F[(StateHash, StateHash, Seq[ProcessedDeploy])]
  def computeBonds(startHash: StateHash): F[Seq[Bond]]
  def getData(hash: StateHash)(channel: Par): F[Seq[Par]]
  def getContinuation(hash: StateHash)(
      channels: Seq[Par]
  ): F[Seq[(Seq[BindPattern], Par)]]
  def emptyStateHash: StateHash
  def withRuntimeLock[A](f: Runtime[F] => F[A]): F[A]
}

class RuntimeManagerImpl[F[_]: Concurrent: Metrics: Span: Log](
    val emptyStateHash: StateHash,
    runtimeContainer: MVar[F, Runtime[F]]
) extends RuntimeManager[F] {
  import coop.rchain.models.rholang.{implicits => toPar}

  private[this] val RuntimeManagerMetricsSource =
    Metrics.Source(CasperMetricsSource, "runtime-manager")
  private[this] val replayComputeStateLabel =
    Metrics.Source(RuntimeManagerMetricsSource, "replay-compute-state")
  private[this] val computeStateLabel = Metrics.Source(RuntimeManagerMetricsSource, "compute-state")
  private[this] val computeGenesisLabel =
    Metrics.Source(RuntimeManagerMetricsSource, "compute-genesis")
  private[this] val systemDeployConsumeAllPattern =
    BindPattern(List(toPar(Expr(EVarBody(EVar(Var(FreeVar(0))))))), freeCount = 1)
  private[this] val emptyContinuation = TaggedContinuation()

  def playSystemDeploy[S <: SystemDeploy](
      runtime: Runtime[F],
      systemDeploy: S,
      rand: Blake2b512Random
  )(normalizerEnv: NormalizerEnv[systemDeploy.Env])(
      implicit ev: ToEnvMap[systemDeploy.Env]
  ): F[Either[SystemDeployFailure, SystemDeployResult[systemDeploy.Result]]] =
    for {
      softCheckpoint <- runtime.space.createSoftCheckpoint()
      result <- EitherT
                 .liftF {
                   implicit val c: _cost[F] = runtime.cost
                   Interpreter[F].injAttempt(
                     runtime.reducer,
                     runtime.errorLog,
                     systemDeploy.source,
                     Cost.UNSAFE_MAX,
                     normalizerEnv.toEnv
                   )(rand)
                 }
                 .ensureOr(evalResult => UnexpectedErrors(evalResult.errors))(
                   !_.isFailed
                 )
                 .flatMap(
                   evalResult =>
                     EitherT
                       .fromOptionF(
                         runtime.space
                           .consume(
                             List(systemDeploy.returnChannel(normalizerEnv)),
                             List(systemDeployConsumeAllPattern),
                             emptyContinuation,
                             persist = false
                           ),
                         ifNone = ConsumeFailed: SystemDeployFailure
                       )
                       .subflatMap {
                         case (_, Seq(Result(_, ListParWithRandom(Seq(output), _), _, _))) =>
                           systemDeploy.extractResult(output)
                         case (_, somethingWeird) =>
                           Left(UnexpectedResult(somethingWeird.flatMap(_.matchedDatum.pars)))
                       }
                       .semiflatMap(
                         deployResult =>
                           runtime.space
                             .createSoftCheckpoint()
                             .map(
                               checkpoint =>
                                 SystemDeployResult(
                                   InternalProcessedSystemDeploy(
                                     Cost.toProto(evalResult.cost),
                                     checkpoint.log
                                   ),
                                   deployResult
                                 )
                             )
                       )
                 )
                 .onError {
                   case _ =>
                     EitherT.right(runtime.space.revertToSoftCheckpoint(softCheckpoint))
                 }
                 .value

    } yield result

  def captureResults(
      start: StateHash,
      deploy: DeployData,
      name: String = "__SCALA__"
  ): F[Seq[Par]] =
    captureResults(start, deploy, Par().withExprs(Seq(Expr(GString(name)))))

  def captureResults(start: StateHash, deploy: DeployData, name: Par): F[Seq[Par]] =
    withResetRuntimeLock(start) { runtime =>
      evaluate(runtime.reducer, runtime.cost, runtime.errorLog)(deploy)
        .ensure(
          BugFoundError("Unexpected error while capturing results from rholang")
        )(
          _.errors.isEmpty
        ) >> getData(runtime)(name)
    }

  def replayComputeState(startHash: StateHash)(
      terms: Seq[ProcessedDeploy],
      blockData: BlockData,
      invalidBlocks: Map[BlockHash, Validator] = Map.empty[BlockHash, Validator],
      isGenesis: Boolean //FIXME have a better way of knowing this. Pass the replayDeploy function maybe?
  ): F[Either[ReplayFailure, StateHash]] =
    withRuntimeLock { runtime =>
      Span[F].trace(replayComputeStateLabel) {
        for {
          _      <- runtime.blockData.set(blockData)
          _      <- setInvalidBlocks(invalidBlocks, runtime)
          _      <- Span[F].mark("before-replay-deploys")
          result <- replayDeploys(runtime, startHash, terms, replayDeploy(runtime))
        } yield result
      }
    }

  def computeState(startHash: StateHash)(
      terms: Seq[DeployData],
      blockData: BlockData,
      invalidBlocks: Map[BlockHash, Validator] = Map.empty[BlockHash, Validator]
  ): F[(StateHash, Seq[ProcessedDeploy])] =
    withRuntimeLock { runtime =>
      Span[F].trace(computeStateLabel) {
        for {
          _      <- runtime.blockData.set(blockData)
          _      <- setInvalidBlocks(invalidBlocks, runtime)
          _      <- Span[F].mark("before-process-deploys")
          result <- processDeploys(runtime, startHash, terms, processDeploy(runtime))
        } yield result
      }
    }

  def computeGenesis(
      terms: Seq[DeployData],
      blockTime: Long
  ): F[(StateHash, StateHash, Seq[ProcessedDeploy])] = {
    val startHash = emptyStateHash
    withRuntimeLock { runtime =>
      Span[F].trace(computeGenesisLabel) {
        for {
          _          <- runtime.blockData.set(BlockData(blockTime, 0))
          _          <- Span[F].mark("before-process-deploys")
          evalResult <- processDeploys(runtime, startHash, terms, processDeploy(runtime))
        } yield (startHash, evalResult._1, evalResult._2)
      }
    }
  }

  private def setInvalidBlocks(
      invalidBlocks: Map[BlockHash, Validator],
      runtime: Runtime[F]
  ): F[Unit] = {
    val invalidBlocksPar: Par =
      Par(
        exprs = Seq(
          Expr(
            Expr.ExprInstance.EMapBody(
              ParMap(SortedParMap(invalidBlocks.map {
                case (validator, blockHash) =>
                  (
                    RhoType.ByteArray(validator.toByteArray),
                    RhoType.ByteArray(blockHash.toByteArray)
                  )
              }))
            )
          )
        )
      )
    runtime.invalidBlocks.setParams(invalidBlocksPar)
  }

  def computeBonds(hash: StateHash): F[Seq[Bond]] =
    captureResults(hash, ConstructDeploy.sourceDeployNow(bondsQuerySource()))
      .ensureOr(
        bondsPar =>
          new IllegalArgumentException(
            s"Incorrect number of results from query of current bonds: ${bondsPar.size}"
          )
      )(bondsPar => bondsPar.size == 1)
      .map { bondsPar =>
        toBondSeq(bondsPar.head)
      }

  private def bondsQuerySource(name: String = "__SCALA__"): String =
    s"""
       # new rl(`rho:registry:lookup`), poSCh in {
       #   rl!(`rho:rchain:pos`, *poSCh) |
       #   for(@(_, PoS) <- poSCh) {
       #     @PoS!("getBonds", "$name")
       #   }
       # }
       """.stripMargin('#')

  def withRuntimeLock[A](f: Runtime[F] => F[A]): F[A] =
    Sync[F].bracket {
      import coop.rchain.metrics.implicits._
      implicit val ms: Source = RuntimeManagerMetricsSource
      for {
        _       <- Metrics[F].incrementGauge("lock.queue")
        runtime <- Sync[F].defer(runtimeContainer.take).timer("lock.acquire")
        _       <- Metrics[F].decrementGauge("lock.queue")
      } yield runtime
    }(f)(runtimeContainer.put)

  private def withResetRuntimeLock[R](hash: StateHash)(block: Runtime[F] => F[R]): F[R] =
    withRuntimeLock(
      runtime => runtime.space.reset(Blake2b256Hash.fromByteString(hash)) >> block(runtime)
    )

  private def toBondSeq(bondsMap: Par): Seq[Bond] =
    bondsMap.exprs.head.getEMapBody.ps.map {
      case (validator: Par, bond: Par) =>
        assert(validator.exprs.length == 1, "Validator in bonds map wasn't a single string.")
        assert(bond.exprs.length == 1, "Stake in bonds map wasn't a single integer.")
        val validatorName = validator.exprs.head.getGByteArray
        val stakeAmount   = bond.exprs.head.getGInt
        Bond(validatorName, stakeAmount)
    }.toList

  def getData(hash: StateHash)(channel: Par): F[Seq[Par]] =
    withResetRuntimeLock(hash)(getData(_)(channel))

  private def getData(
      runtime: Runtime[F]
  )(channel: Par): F[Seq[Par]] =
    runtime.space.getData(channel).map(_.flatMap(_.a.pars))

  def getContinuation(
      hash: StateHash
  )(channels: Seq[Par]): F[Seq[(Seq[BindPattern], Par)]] =
    withResetRuntimeLock(hash)(
      _.space
        .getWaitingContinuations(channels)
        .map(
          _.filter(_.continuation.taggedCont.isParBody)
            .map(result => (result.patterns, result.continuation.taggedCont.parBody.get.body))
        )
    )

  private def processDeploys(
      runtime: Runtime[F],
      startHash: StateHash,
      terms: Seq[DeployData],
      processDeploy: DeployData => F[ProcessedDeploy]
  ): F[(StateHash, Seq[ProcessedDeploy])] = {
    import cats.instances.list._

    for {
      _               <- runtime.space.reset(Blake2b256Hash.fromByteString(startHash))
      res             <- terms.toList.traverse(processDeploy)
      _               <- Span[F].mark("before-process-deploys-create-checkpoint")
      finalCheckpoint <- runtime.space.createCheckpoint()
      finalStateHash  = finalCheckpoint.root
    } yield (finalStateHash.toByteString, res)
  }

  private def processDeploy(runtime: Runtime[F])(
      deploy: DeployData
  ): F[ProcessedDeploy] = Span[F].withMarks("process-deploy") {
    for {
      fallback                     <- runtime.space.createSoftCheckpoint()
      evaluateResult               <- evaluate(runtime.reducer, runtime.cost, runtime.errorLog)(deploy)
      EvaluateResult(cost, errors) = evaluateResult
      _                            <- Span[F].mark("before-process-deploy-create-soft-checkpoint")
      checkpoint                   <- runtime.space.createSoftCheckpoint()
      deployResult = ProcessedDeploy(
        deploy,
        Cost.toProto(cost),
        checkpoint.log.map(EventConverter.toCasperEvent).toList,
        errors.nonEmpty
      )
      _ <- if (errors.nonEmpty) runtime.space.revertToSoftCheckpoint(fallback)
          else Applicative[F].unit
    } yield deployResult
  }

  private def replayDeploys(
      runtime: Runtime[F],
      startHash: StateHash,
      terms: Seq[ProcessedDeploy],
      replayDeploy: ProcessedDeploy => F[Option[ReplayFailure]]
  ): F[Either[ReplayFailure, StateHash]] =
    (for {
      _ <- EitherT.right(runtime.replaySpace.reset(Blake2b256Hash.fromByteString(startHash)))
      _ <- EitherT(
            terms.tailRecM { ts =>
              if (ts.isEmpty)
                ().asRight[ReplayFailure].asRight[Seq[ProcessedDeploy]].pure[F]
              else replayDeploy(ts.head).map(_.map(_.asLeft[Unit]).toRight(ts.tail))
            }
          )
      _   <- EitherT.right(Span[F].mark("before-replay-deploys-create-checkpoint"))
      res <- EitherT.right[ReplayFailure](runtime.replaySpace.createCheckpoint())
    } yield res.root.toByteString).value

  private def replayDeploy(runtime: Runtime[F])(
      processedDeploy: ProcessedDeploy
  ): F[Option[ReplayFailure]] = Span[F].withMarks("replay-deploy") {
    import processedDeploy._
    for {
      _              <- runtime.replaySpace.rig(processedDeploy.deployLog.map(EventConverter.toRspaceEvent))
      softCheckpoint <- runtime.replaySpace.createSoftCheckpoint()
      _              <- Span[F].mark("before-replay-deploy-compute-effect")
      failureOption <- EitherT
                        .liftF(
                          evaluate(runtime.replayReducer, runtime.cost, runtime.errorLog)(
                            processedDeploy.deploy
                          )
                        )
                        .ensureOr(
                          /* Regardless of success or failure, verify that deploy status' match. */
                          result => ReplayFailure.replayStatusMismatch(isFailed, result.failed)
                        )(result => isFailed == result.failed)
                        .ensureOr(
                          result =>
                            /* Since there are no errors, verify evaluation costs and COMM events match. */
                            ReplayFailure.replayCostMismatch(deploy, cost.cost, result.cost.value)
                        )(result => result.failed || cost.cost == result.cost.value)
                        .semiflatMap(
                          result =>
                            if (result.failed)
                              /* Since the state of `replaySpace` is reset on each invocation of `replayComputeState`,
                                 and `ReplayFailure`s mean that block processing is cancelled upstream, we only need to
                                 reset state if the replay effects of valid deploys need to be discarded. */
                              /* Error-throwing Rholang programs do not have deterministic evaluation costs
                                 and event logs, so they cannot be reliably compared. */
                              runtime.replaySpace.revertToSoftCheckpoint(softCheckpoint)
                            else runtime.replaySpace.checkReplayData()
                        )
                        .swap
                        .toOption
                        .value
                        .recover {
                          case replayException: ReplayException =>
                            ReplayFailure.unusedCOMMEvent(deploy, replayException).some
                          case throwable => ReplayFailure.internalError(deploy, throwable).some
                        }
    } yield failureOption
  }
}

object RuntimeManager {

  type StateHash = ByteString

  def fromRuntime[F[_]: Concurrent: Sync: Metrics: Span: Log](
      runtime: Runtime[F]
  ): F[RuntimeManager[F]] =
    for {
      _                <- runtime.space.clear()
      _                <- runtime.replaySpace.clear()
      _                <- Runtime.bootstrapRegistry(runtime)
      checkpoint       <- runtime.space.createCheckpoint()
      replayCheckpoint <- runtime.replaySpace.createCheckpoint()
      hash             = ByteString.copyFrom(checkpoint.root.bytes.toArray)
      replayHash       = ByteString.copyFrom(replayCheckpoint.root.bytes.toArray)
      _                = assert(hash == replayHash)
      runtime          <- MVar[F].of(runtime)
    } yield new RuntimeManagerImpl(hash, runtime)

  def evaluate[F[_]: Sync](
      reducer: Reduce[F],
      costState: _cost[F],
      errorLog: ErrorLog[F]
  )(deploy: DeployData): F[EvaluateResult] = {
    import rholang.implicits._
    implicit val rand: Blake2b512Random = Blake2b512Random(
      ProtoUtil.stripDeployData(deploy).toProto.toByteArray
    )
    implicit val cost: _cost[F] = costState
    Interpreter[F].injAttempt(
      reducer,
      errorLog,
      deploy.term,
      Cost(deploy.phloLimit),
      NormalizerEnv(deploy).toEnv
    )
  }
}
