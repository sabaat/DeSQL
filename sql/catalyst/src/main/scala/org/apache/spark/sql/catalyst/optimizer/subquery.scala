/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import scala.collection.mutable.ArrayBuffer

import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.CleanupAliases
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.SubExprUtils._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules._
import org.apache.spark.sql.types._

/*
 * This file defines optimization rules related to subqueries.
 */


/**
 * This rule rewrites predicate sub-queries into left semi/anti joins. The following predicates
 * are supported:
 * a. EXISTS/NOT EXISTS will be rewritten as semi/anti join, unresolved conditions in Filter
 *    will be pulled out as the join conditions.
 * b. IN/NOT IN will be rewritten as semi/anti join, unresolved conditions in the Filter will
 *    be pulled out as join conditions, value = selected column will also be used as join
 *    condition.
 */
object RewritePredicateSubquery extends Rule[LogicalPlan] with PredicateHelper {

  private def buildJoin(
      outerPlan: LogicalPlan,
      subplan: LogicalPlan,
      joinType: JoinType,
      condition: Option[Expression]): Join = {
    // Deduplicate conflicting attributes if any.
    val dedupSubplan = dedupSubqueryOnSelfJoin(outerPlan, subplan, None, condition)
    Join(outerPlan, dedupSubplan, joinType, condition, JoinHint.NONE)
  }

  private def dedupSubqueryOnSelfJoin(
      outerPlan: LogicalPlan,
      subplan: LogicalPlan,
      valuesOpt: Option[Seq[Expression]],
      condition: Option[Expression] = None): LogicalPlan = {
    // SPARK-21835: It is possibly that the two sides of the join have conflicting attributes,
    // the produced join then becomes unresolved and break structural integrity. We should
    // de-duplicate conflicting attributes.
    // SPARK-26078: it may also happen that the subquery has conflicting attributes with the outer
    // values. In this case, the resulting join would contain trivially true conditions (eg.
    // id#3 = id#3) which cannot be de-duplicated after. In this method, if there are conflicting
    // attributes in the join condition, the subquery's conflicting attributes are changed using
    // a projection which aliases them and resolves the problem.
    val outerReferences = valuesOpt.map(values =>
      AttributeSet.fromAttributeSets(values.map(_.references))).getOrElse(AttributeSet.empty)
    val outerRefs = outerPlan.outputSet ++ outerReferences
    val duplicates = outerRefs.intersect(subplan.outputSet)
    if (duplicates.nonEmpty) {
      condition.foreach { e =>
          val conflictingAttrs = e.references.intersect(duplicates)
          if (conflictingAttrs.nonEmpty) {
            throw new AnalysisException("Found conflicting attributes " +
              s"${conflictingAttrs.mkString(",")} in the condition joining outer plan:\n  " +
              s"$outerPlan\nand subplan:\n  $subplan")
          }
      }
      val rewrites = AttributeMap(duplicates.map { dup =>
        dup -> Alias(dup, dup.toString)()
      }.toSeq)
      val aliasedExpressions = subplan.output.map { ref =>
        rewrites.getOrElse(ref, ref)
      }
      Project(aliasedExpressions, subplan)
    } else {
      subplan
    }
  }

  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case Filter(condition, child) =>
      val (withSubquery, withoutSubquery) =
        splitConjunctivePredicates(condition)
          .partition(SubqueryExpression.hasInOrCorrelatedExistsSubquery)

      // Construct the pruned filter condition.
      val newFilter: LogicalPlan = withoutSubquery match {
        case Nil => child
        case conditions => Filter(conditions.reduce(And), child)
      }
      if(plan.isInstanceOf[Filter]){
        newFilter.setMappingIndex(plan.getMappingIndex)
      }
      var subQueryMappingIndex = -1
      if(withSubquery.size > 0) subQueryMappingIndex = withSubquery(0).getMappingIndex()
      // Filter the plan by applying left semi and left anti joins.
      withSubquery.foldLeft(newFilter) {
        case (p, Exists(sub, conditions, _)) =>
          val (joinCond, outerPlan) = rewriteExistentialExpr(conditions, p)
          buildJoin(outerPlan, sub, LeftSemi, joinCond)
        case (p, Not(Exists(sub, conditions, _))) =>
          val (joinCond, outerPlan) = rewriteExistentialExpr(conditions, p)
          buildJoin(outerPlan, sub, LeftAnti, joinCond)
        case (p, InSubquery(values, ListQuery(sub, conditions, _, _))) =>
          // Deduplicate conflicting attributes if any.
          val newSub = dedupSubqueryOnSelfJoin(p, sub, Some(values))
          val inConditions = values.zip(newSub.output).map(EqualTo.tupled)
          val (joinCond, outerPlan) = rewriteExistentialExpr(inConditions ++ conditions, p)
          var getJoinInstance = Join(outerPlan, newSub, LeftSemi, joinCond, JoinHint.NONE)
          getJoinInstance.setMappingIndex(subQueryMappingIndex)
          getJoinInstance
        case (p, Not(InSubquery(values, ListQuery(sub, conditions, _, _)))) =>
          // This is a NULL-aware (left) anti join (NAAJ) e.g. col NOT IN expr
          // Construct the condition. A NULL in one of the conditions is regarded as a positive
          // result; such a row will be filtered out by the Anti-Join operator.

          // Note that will almost certainly be planned as a Broadcast Nested Loop join.
          // Use EXISTS if performance matters to you.

          // Deduplicate conflicting attributes if any.
          val newSub = dedupSubqueryOnSelfJoin(p, sub, Some(values))
          val inConditions = values.zip(newSub.output).map(EqualTo.tupled)
          val (joinCond, outerPlan) = rewriteExistentialExpr(inConditions, p)
          // Expand the NOT IN expression with the NULL-aware semantic
          // to its full form. That is from:
          //   (a1,a2,...) = (b1,b2,...)
          // to
          //   (a1=b1 OR isnull(a1=b1)) AND (a2=b2 OR isnull(a2=b2)) AND ...
          val baseJoinConds = splitConjunctivePredicates(joinCond.get)
          val nullAwareJoinConds = baseJoinConds.map(c => Or(c, IsNull(c)))
          // After that, add back the correlated join predicate(s) in the subquery
          // Example:
          // SELECT ... FROM A WHERE A.A1 NOT IN (SELECT B.B1 FROM B WHERE B.B2 = A.A2 AND B.B3 > 1)
          // will have the final conditions in the LEFT ANTI as
          // (A.A1 = B.B1 OR ISNULL(A.A1 = B.B1)) AND (B.B2 = A.A2) AND B.B3 > 1
          val finalJoinCond = (nullAwareJoinConds ++ conditions).reduceLeft(And)
          Join(outerPlan, newSub, LeftAnti, Option(finalJoinCond), JoinHint.NONE)
        case (p, predicate) =>
          val (newCond, inputPlan) = rewriteExistentialExpr(Seq(predicate), p)
          Project(p.output, Filter(newCond.get, inputPlan))
      }
  }

  /**
   * Given a predicate expression and an input plan, it rewrites any embedded existential sub-query
   * into an existential join. It returns the rewritten expression together with the updated plan.
   * Currently, it does not support NOT IN nested inside a NOT expression. This case is blocked in
   * the Analyzer.
   */
  private def rewriteExistentialExpr(
      exprs: Seq[Expression],
      plan: LogicalPlan): (Option[Expression], LogicalPlan) = {
    var newPlan = plan
    val newExprs = exprs.map { e =>
      e transformUp {
        case Exists(sub, conditions, _) =>
          val exists = AttributeReference("exists", BooleanType, nullable = false)()
          newPlan =
            buildJoin(newPlan, sub, ExistenceJoin(exists), conditions.reduceLeftOption(And))
          exists
        case InSubquery(values, ListQuery(sub, conditions, _, _)) =>
          val exists = AttributeReference("exists", BooleanType, nullable = false)()
          // Deduplicate conflicting attributes if any.
          val newSub = dedupSubqueryOnSelfJoin(newPlan, sub, Some(values))
          val inConditions = values.zip(newSub.output).map(EqualTo.tupled)
          val newConditions = (inConditions ++ conditions).reduceLeftOption(And)
          newPlan = Join(newPlan, newSub, ExistenceJoin(exists), newConditions, JoinHint.NONE)
          exists
      }
    }
    (newExprs.reduceOption(And), newPlan)
  }
}

 /**
  * Pull out all (outer) correlated predicates from a given subquery. This method removes the
  * correlated predicates from subquery [[Filter]]s and adds the references of these predicates
  * to all intermediate [[Project]] and [[Aggregate]] clauses (if they are missing) in order to
  * be able to evaluate the predicates at the top level.
  *
  * TODO: Look to merge this rule with RewritePredicateSubquery.
  */
object PullupCorrelatedPredicates extends Rule[LogicalPlan] with PredicateHelper {
   /**
    * Returns the correlated predicates and a updated plan that removes the outer references.
    */
  private def pullOutCorrelatedPredicates(
      sub: LogicalPlan,
      outer: Seq[LogicalPlan]): (LogicalPlan, Seq[Expression]) = {
    val predicateMap = scala.collection.mutable.Map.empty[LogicalPlan, Seq[Expression]]

    /** Determine which correlated predicate references are missing from this plan. */
    def missingReferences(p: LogicalPlan): AttributeSet = {
      val localPredicateReferences = p.collect(predicateMap)
        .flatten
        .map(_.references)
        .reduceOption(_ ++ _)
        .getOrElse(AttributeSet.empty)
      localPredicateReferences -- p.outputSet
    }

    // Simplify the predicates before pulling them out.
    val transformed = BooleanSimplification(sub) transformUp {
      case f @ Filter(cond, child) =>
        val (correlated, local) =
          splitConjunctivePredicates(cond).partition(containsOuter)

        // Rewrite the filter without the correlated predicates if any.
        correlated match {
          case Nil => f
          case xs if local.nonEmpty =>
            val newFilter = Filter(local.reduce(And), child)
            predicateMap += newFilter -> xs
            newFilter
          case xs =>
            predicateMap += child -> xs
            child
        }
      case p @ Project(expressions, child) =>
        val referencesToAdd = missingReferences(p)
        if (referencesToAdd.nonEmpty) {
          Project(expressions ++ referencesToAdd, child)
        } else {
          p
        }
      case a @ Aggregate(grouping, expressions, child) =>
        val referencesToAdd = missingReferences(a)
        if (referencesToAdd.nonEmpty) {
          Aggregate(grouping ++ referencesToAdd, expressions ++ referencesToAdd, child)
        } else {
          a
        }
      case p =>
        p
    }

    // Make sure the inner and the outer query attributes do not collide.
    // In case of a collision, change the subquery plan's output to use
    // different attribute by creating alias(s).
    val baseConditions = predicateMap.values.flatten.toSeq
    val (newPlan, newCond) = if (outer.nonEmpty) {
      val outputSet = outer.map(_.outputSet).reduce(_ ++ _)
      val duplicates = transformed.outputSet.intersect(outputSet)
      val (plan, deDuplicatedConditions) = if (duplicates.nonEmpty) {
        val aliasMap = AttributeMap(duplicates.map { dup =>
          dup -> Alias(dup, dup.toString)()
        }.toSeq)
        val aliasedExpressions = transformed.output.map { ref =>
          aliasMap.getOrElse(ref, ref)
        }
        val aliasedProjection = Project(aliasedExpressions, transformed)
        val aliasedConditions = baseConditions.map(_.transform {
          case ref: Attribute => aliasMap.getOrElse(ref, ref).toAttribute
        })
        (aliasedProjection, aliasedConditions)
      } else {
        (transformed, baseConditions)
      }
      (plan, stripOuterReferences(deDuplicatedConditions))
    } else {
      (transformed, stripOuterReferences(baseConditions))
    }
    (newPlan, newCond)
  }

  private def rewriteSubQueries(plan: LogicalPlan, outerPlans: Seq[LogicalPlan]): LogicalPlan = {
    /**
     * This function is used as a aid to enforce idempotency of pullUpCorrelatedPredicate rule.
     * In the first call to rewriteSubqueries, all the outer references from the subplan are
     * pulled up and join predicates are recorded as children of the enclosing subquery expression.
     * The subsequent call to rewriteSubqueries would simply re-records the `children` which would
     * contains the pulled up correlated predicates (from the previous call) in the enclosing
     * subquery expression.
     */
    def getJoinCondition(newCond: Seq[Expression], oldCond: Seq[Expression]): Seq[Expression] = {
      if (newCond.isEmpty) oldCond else newCond
    }

    plan transformExpressions {
      case ScalarSubquery(sub, children, exprId) if children.nonEmpty =>
        val (newPlan, newCond) = pullOutCorrelatedPredicates(sub, outerPlans)
        ScalarSubquery(newPlan, getJoinCondition(newCond, children), exprId)
      case Exists(sub, children, exprId) if children.nonEmpty =>
        val (newPlan, newCond) = pullOutCorrelatedPredicates(sub, outerPlans)
        Exists(newPlan, getJoinCondition(newCond, children), exprId)
      case ListQuery(sub, children, exprId, childOutputs) if children.nonEmpty =>
        val (newPlan, newCond) = pullOutCorrelatedPredicates(sub, outerPlans)
        ListQuery(newPlan, getJoinCondition(newCond, children), exprId, childOutputs)
    }
  }

  /**
   * Pull up the correlated predicates and rewrite all subqueries in an operator tree..
   */
  def apply(plan: LogicalPlan): LogicalPlan = plan transformUp {
    case f @ Filter(_, a: Aggregate) =>
      rewriteSubQueries(f, Seq(a, a.child))
    // Only a few unary nodes (Project/Filter/Aggregate) can contain subqueries.
    case q: UnaryNode =>
      rewriteSubQueries(q, q.children)
  }
}

/**
 * This rule rewrites correlated [[ScalarSubquery]] expressions into LEFT OUTER joins.
 */
object RewriteCorrelatedScalarSubquery extends Rule[LogicalPlan] {
  /**
   * Extract all correlated scalar subqueries from an expression. The subqueries are collected using
   * the given collector. The expression is rewritten and returned.
   */
  private def extractCorrelatedScalarSubqueries[E <: Expression](
      expression: E,
      subqueries: ArrayBuffer[ScalarSubquery]): E = {
    val newExpression = expression transform {
      case s: ScalarSubquery if s.children.nonEmpty =>
        subqueries += s
        s.plan.output.head
    }
    newExpression.asInstanceOf[E]
  }

  /**
   * Checks if given expression is foldable. Evaluates it and returns it as literal, if yes.
   * If not, returns the original expression without evaluation.
   */
  private def tryEvalExpr(expr: Expression): Expression = {
    // Removes Alias over given expression, because Alias is not foldable.
    if (!CleanupAliases.trimAliases(expr).foldable) {
      // SPARK-28441: Some expressions, like PythonUDF, can't be statically evaluated.
      // Needs to evaluate them on query runtime.
      expr
    } else {
      Literal.create(expr.eval(), expr.dataType)
    }
  }

  /**
   * Statically evaluate an expression containing zero or more placeholders, given a set
   * of bindings for placeholder values, if the expression is evaluable. If it is not,
   * bind statically evaluated expression results to an expression.
   */
  private def bindingExpr(
      expr: Expression,
      bindings: Map[ExprId, Expression]): Expression = {
    val rewrittenExpr = expr transform {
      case r: AttributeReference =>
        bindings.getOrElse(r.exprId, Literal.default(NullType))
    }

    tryEvalExpr(rewrittenExpr)
  }

  /**
   * Statically evaluate an expression containing one or more aggregates on an empty input.
   */
  private def evalAggOnZeroTups(expr: Expression) : Expression = {
    // AggregateExpressions are Unevaluable, so we need to replace all aggregates
    // in the expression with the value they would return for zero input tuples.
    // Also replace attribute refs (for example, for grouping columns) with NULL.
    val rewrittenExpr = expr transform {
      case a @ AggregateExpression(aggFunc, _, _, resultId, _) =>
        aggFunc.defaultResult.getOrElse(Literal.default(NullType))

      case _: AttributeReference => Literal.default(NullType)
    }

    tryEvalExpr(rewrittenExpr)
  }

  /**
   * Statically evaluate a scalar subquery on an empty input.
   *
   * <b>WARNING:</b> This method only covers subqueries that pass the checks under
   * [[org.apache.spark.sql.catalyst.analysis.CheckAnalysis]]. If the checks in
   * CheckAnalysis become less restrictive, this method will need to change.
   */
  private def evalSubqueryOnZeroTups(plan: LogicalPlan) : Option[Expression] = {
    // Inputs to this method will start with a chain of zero or more SubqueryAlias
    // and Project operators, followed by an optional Filter, followed by an
    // Aggregate. Traverse the operators recursively.
    def evalPlan(lp : LogicalPlan) : Map[ExprId, Expression] = lp match {
      case SubqueryAlias(_, child) => evalPlan(child)
      case Filter(condition, child) =>
        val bindings = evalPlan(child)
        if (bindings.isEmpty) {
          bindings
        } else {
          val bindCondition = bindingExpr(condition, bindings)

          if (!bindCondition.foldable) {
            // We can't evaluate the condition. Evaluate it in query runtime.
            bindings.map { case (id, expr) =>
              val newExpr = If(bindCondition, expr, Literal.create(null, expr.dataType))
              (id, newExpr)
            }
          } else {
            // The bound condition can be evaluated.
            bindCondition.eval() match {
              // For filter condition, null is the same as false.
              case null | false => Map.empty
              case true => bindings
            }
          }
        }

      case Project(projectList, child) =>
        val bindings = evalPlan(child)
        if (bindings.isEmpty) {
          bindings
        } else {
          projectList.map(ne => (ne.exprId, bindingExpr(ne, bindings))).toMap
        }

      case Aggregate(_, aggExprs, _) =>
        // Some of the expressions under the Aggregate node are the join columns
        // for joining with the outer query block. Fill those expressions in with
        // nulls and statically evaluate the remainder.
        aggExprs.map {
          case ref: AttributeReference => (ref.exprId, Literal.create(null, ref.dataType))
          case alias @ Alias(_: AttributeReference, _) =>
            (alias.exprId, Literal.create(null, alias.dataType))
          case ne => (ne.exprId, evalAggOnZeroTups(ne))
        }.toMap

      case _ =>
        sys.error(s"Unexpected operator in scalar subquery: $lp")
    }

    val resultMap = evalPlan(plan)

    // By convention, the scalar subquery result is the leftmost field.
    resultMap.get(plan.output.head.exprId) match {
      case Some(Literal(null, _)) | None => None
      case o => o
    }
  }

  /**
   * Split the plan for a scalar subquery into the parts above the innermost query block
   * (first part of returned value), the HAVING clause of the innermost query block
   * (optional second part) and the parts below the HAVING CLAUSE (third part).
   */
  private def splitSubquery(plan: LogicalPlan) : (Seq[LogicalPlan], Option[Filter], Aggregate) = {
    val topPart = ArrayBuffer.empty[LogicalPlan]
    var bottomPart: LogicalPlan = plan
    while (true) {
      bottomPart match {
        case havingPart @ Filter(_, aggPart: Aggregate) =>
          return (topPart, Option(havingPart), aggPart)

        case aggPart: Aggregate =>
          // No HAVING clause
          return (topPart, None, aggPart)

        case p @ Project(_, child) =>
          topPart += p
          bottomPart = child

        case s @ SubqueryAlias(_, child) =>
          topPart += s
          bottomPart = child

        case Filter(_, op) =>
          sys.error(s"Correlated subquery has unexpected operator $op below filter")

        case op @ _ => sys.error(s"Unexpected operator $op in correlated subquery")
      }
    }

    sys.error("This line should be unreachable")
  }

  // Name of generated column used in rewrite below
  val ALWAYS_TRUE_COLNAME = "alwaysTrue"

  /**
   * Construct a new child plan by left joining the given subqueries to a base plan.
   */
  private def constructLeftJoins(
      child: LogicalPlan,
      subqueries: ArrayBuffer[ScalarSubquery]): LogicalPlan = {
    subqueries.foldLeft(child) {
      case (currentChild, ScalarSubquery(query, conditions, _)) =>
        val origOutput = query.output.head

        val resultWithZeroTups = evalSubqueryOnZeroTups(query)
        if (resultWithZeroTups.isEmpty) {
          // CASE 1: Subquery guaranteed not to have the COUNT bug
          Project(
            currentChild.output :+ origOutput,
            Join(currentChild, query, LeftOuter, conditions.reduceOption(And), JoinHint.NONE))
        } else {
          // Subquery might have the COUNT bug. Add appropriate corrections.
          val (topPart, havingNode, aggNode) = splitSubquery(query)

          // The next two cases add a leading column to the outer join input to make it
          // possible to distinguish between the case when no tuples join and the case
          // when the tuple that joins contains null values.
          // The leading column always has the value TRUE.
          val alwaysTrueExprId = NamedExpression.newExprId
          val alwaysTrueExpr = Alias(Literal.TrueLiteral,
            ALWAYS_TRUE_COLNAME)(exprId = alwaysTrueExprId)
          val alwaysTrueRef = AttributeReference(ALWAYS_TRUE_COLNAME,
            BooleanType)(exprId = alwaysTrueExprId)

          val aggValRef = query.output.head

          if (havingNode.isEmpty) {
            // CASE 2: Subquery with no HAVING clause
            Project(
              currentChild.output :+
                Alias(
                  If(IsNull(alwaysTrueRef),
                    resultWithZeroTups.get,
                    aggValRef), origOutput.name)(exprId = origOutput.exprId),
              Join(currentChild,
                Project(query.output :+ alwaysTrueExpr, query),
                LeftOuter, conditions.reduceOption(And), JoinHint.NONE))

          } else {
            // CASE 3: Subquery with HAVING clause. Pull the HAVING clause above the join.
            // Need to modify any operators below the join to pass through all columns
            // referenced in the HAVING clause.
            var subqueryRoot: UnaryNode = aggNode
            val havingInputs: Seq[NamedExpression] = aggNode.output

            topPart.reverse.foreach {
              case Project(projList, _) =>
                subqueryRoot = Project(projList ++ havingInputs, subqueryRoot)
              case s @ SubqueryAlias(alias, _) =>
                subqueryRoot = SubqueryAlias(alias, subqueryRoot)
              case op => sys.error(s"Unexpected operator $op in corelated subquery")
            }

            // CASE WHEN alwaysTrue IS NULL THEN resultOnZeroTups
            //      WHEN NOT (original HAVING clause expr) THEN CAST(null AS <type of aggVal>)
            //      ELSE (aggregate value) END AS (original column name)
            val caseExpr = Alias(CaseWhen(Seq(
              (IsNull(alwaysTrueRef), resultWithZeroTups.get),
              (Not(havingNode.get.condition), Literal.create(null, aggValRef.dataType))),
              aggValRef),
              origOutput.name)(exprId = origOutput.exprId)

            Project(
              currentChild.output :+ caseExpr,
              Join(currentChild,
                Project(subqueryRoot.output :+ alwaysTrueExpr, subqueryRoot),
                LeftOuter, conditions.reduceOption(And), JoinHint.NONE))

          }
        }
    }
  }

  /**
   * Rewrite [[Filter]], [[Project]] and [[Aggregate]] plans containing correlated scalar
   * subqueries.
   */
  def apply(plan: LogicalPlan): LogicalPlan = plan transform {
    case a @ Aggregate(grouping, expressions, child) =>
      val subqueries = ArrayBuffer.empty[ScalarSubquery]
      val newExpressions = expressions.map(extractCorrelatedScalarSubqueries(_, subqueries))
      if (subqueries.nonEmpty) {
        // We currently only allow correlated subqueries in an aggregate if they are part of the
        // grouping expressions. As a result we need to replace all the scalar subqueries in the
        // grouping expressions by their result.
        val newGrouping = grouping.map { e =>
          subqueries.find(_.semanticEquals(e)).map(_.plan.output.head).getOrElse(e)
        }
        Aggregate(newGrouping, newExpressions, constructLeftJoins(child, subqueries))
      } else {
        a
      }
    case p @ Project(expressions, child) =>
      val subqueries = ArrayBuffer.empty[ScalarSubquery]
      val newExpressions = expressions.map(extractCorrelatedScalarSubqueries(_, subqueries))
      if (subqueries.nonEmpty) {
        Project(newExpressions, constructLeftJoins(child, subqueries))
      } else {
        p
      }
    case f @ Filter(condition, child) =>
      val subqueries = ArrayBuffer.empty[ScalarSubquery]
      val newCondition = extractCorrelatedScalarSubqueries(condition, subqueries)
      if (subqueries.nonEmpty) {
        Project(f.output, Filter(newCondition, constructLeftJoins(child, subqueries)))
      } else {
        f
      }
  }
}
