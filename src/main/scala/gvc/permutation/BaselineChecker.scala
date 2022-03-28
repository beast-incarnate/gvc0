package gvc.permutation
import gvc.transformer.{IRGraph => IR}
import gvc.weaver._

object BaselineChecker {
  def check(program: IR.Program): Unit = {
    val structIds =
      program.structs.map(s => (s.name, s.addField("_id", IR.IntType))).toMap
    val runtime = CheckRuntime.addToIR(program)
    val checks = new CheckImplementation(program, runtime, structIds)

    program.methods.foreach(checkMethod(_, checks))
  }

  def checkMethod(
      method: IR.Method,
      checks: CheckImplementation
  ): Unit = {
    val perms = method.name match {
      case "main" =>
        method.addVar(
          checks.runtime.ownedFieldsRef,
          CheckRuntime.Names.primaryOwnedFields
        )
      case _ =>
        method.addParameter(
          checks.runtime.ownedFieldsRef,
          CheckRuntime.Names.primaryOwnedFields
        )
    }

    val tempPerms = method.addVar(
      checks.runtime.ownedFieldsRef,
      CheckRuntime.Names.temporaryOwnedFields
    )

    checkBlock(method.body, checks, perms, tempPerms)

    method.precondition.toSeq.flatMap(
      validateSpec(_, perms, tempPerms, checks)
    ) ++=: method.body

    if (Collector.hasImplicitReturn(method)) {
      method.body ++= method.postcondition.toSeq.flatMap(
        validateSpec(_, perms, tempPerms, checks)
      )
    }

    // Add the initialization to main
    if (method.name == "main") {
      val instanceCounter = method.addVar(
        new IR.PointerType(IR.IntType),
        CheckRuntime.Names.instanceCounter
      )

      Seq(
        new IR.AllocValue(IR.IntType, instanceCounter),
        new IR.Invoke(
          checks.runtime.initOwnedFields,
          List(instanceCounter),
          Some(perms)
        )
      ) ++=: method.body
    }
  }

  def equivalentFields(x: IR.Member, y: IR.Member): Boolean = {
    (x, y) match {
      case (xf: IR.FieldMember, yf: IR.FieldMember) =>
        xf.field == yf.field && ((xf.root, yf.root) match {
          case (xr: IR.Var, yr: IR.Var) => xr == yr
          case (xr: IR.FieldMember, yr: IR.FieldMember) =>
            equivalentFields(xr, yr)
          case _ => false
        })
      case _ => false
    }
  }

  def validateAccess(
      expr: IR.Expression,
      perms: IR.Var,
      checks: CheckImplementation,
      context: SpecificationContext = ValueContext,
      inSpec: Boolean = false,
      fieldAccs: List[IR.Member] = Nil
  ): (Seq[IR.Op], List[IR.Member]) = expr match {
    case acc: IR.Accessibility =>
      // Check framing
      val (ops, fields) = validateAccess(
        acc.member.root,
        perms,
        checks,
        context,
        inSpec,
        fieldAccs
      )
      (ops, acc.member :: fields)

    case cond: IR.Conditional => {
      val (initial, fields) =
        validateAccess(cond.condition, perms, checks, context, false, fieldAccs)
      val (ifTrue, _) =
        validateAccess(cond.ifTrue, perms, checks, context, inSpec, fieldAccs)
      val (ifFalse, _) =
        validateAccess(cond.ifFalse, perms, checks, context, inSpec, fieldAccs)

      if (ifTrue.isEmpty && ifFalse.isEmpty) {
        (initial, fields)
      } else if (ifTrue.isEmpty) {
        val iff = new IR.If(new IR.Unary(IR.UnaryOp.Not, cond.condition))
        iff.ifTrue ++= ifFalse
        (initial :+ iff, fields)
      } else if (ifFalse.isEmpty) {
        val iff = new IR.If(cond.condition)
        iff.ifTrue ++= ifTrue
        (initial :+ iff, fields)
      } else {
        val iff = new IR.If(cond.condition)
        iff.ifTrue ++= ifTrue
        iff.ifFalse ++= ifFalse
        (initial :+ iff, fields)
      }
    }

    case b: IR.Binary => {
      val subSpec = inSpec && b.operator == IR.BinaryOp.And
      val (left, leftFields) =
        validateAccess(b.left, perms, checks, context, subSpec, fieldAccs)
      val (right, rightFields) =
        validateAccess(b.right, perms, checks, context, subSpec, leftFields)

      if (right.isEmpty) {
        (left, leftFields)
      } else {
        b.operator match {
          // If we are in the top-level of a specification, the conditions must all
          // be satisfied anyway, and we cannot switch based on the condition value
          // (e.g. we cannot check if an acc() is true).

          // But, if we are not in a spec, the short-circuiting behavior of AND
          // must be followed
          case IR.BinaryOp.And if !inSpec =>
            val iff = new IR.If(b.left)
            iff.ifTrue ++= right
            (left :+ iff, leftFields)

          case IR.BinaryOp.Or =>
            val iff = new IR.If(new IR.Unary(IR.UnaryOp.Not, b.left))
            iff.ifTrue ++= right
            (left :+ iff, leftFields)

          case _ =>
            (left ++ right, rightFields)
        }
      }
    }

    case u: IR.Unary =>
      validateAccess(u.operand, perms, checks, context, false, fieldAccs)
    case imp: IR.Imprecise =>
      imp.precise match {
        case None => (Seq.empty, fieldAccs)
        case Some(precise) =>
          validateAccess(precise, perms, checks, context, inSpec, fieldAccs)
      }
    case _: IR.Literal | _: IR.Result | _: IR.Var =>
      (Seq.empty, fieldAccs)

    case field: IR.FieldMember =>
      val (rootOps, fields) =
        validateAccess(field.root, perms, checks, context, inSpec, fieldAccs)
      if (fields.exists(equivalentFields(_, field))) {
        (rootOps, fields)
      } else {
        val acc =
          checks.translateFieldPermission(VerifyMode, field, perms, context)
        (rootOps ++ acc, field :: fields)
      }

    case pred: IR.PredicateInstance =>
      var fields = fieldAccs
      val arguments = pred.arguments.flatMap(arg => {
        val (argOps, argFields) =
          validateAccess(arg, perms, checks, context, false, fields)
        fields = argFields
        argOps
      })
      (arguments, fields)

    case _: IR.ArrayMember | _: IR.DereferenceMember =>
      throw new WeaverException("Invalid member")
  }

  def validateSpec(
      expr: IR.Expression,
      primaryPerms: IR.Var,
      tempPerms: IR.Var,
      checks: CheckImplementation,
      context: SpecificationContext = ValueContext
  ): Seq[IR.Op] = {
    val (access, _) =
      validateAccess(expr, primaryPerms, checks, context, true, Nil)
    val verify = checks.translate(VerifyMode, expr, primaryPerms, context)

    if (verify.isEmpty) {
      // If there are no checks in the specification, there will be no separation checks
      access
    } else {
      val separation =
        checks.translate(SeparationMode, expr, tempPerms, context)
      if (separation.isEmpty) {
        access ++ verify
      } else {
        Seq.concat(
          access,
          verify,
          Seq(
            new IR.Invoke(
              checks.runtime.initOwnedFields,
              List(
                new IR.FieldMember(
                  primaryPerms,
                  checks.runtime.ownedFieldInstanceCounter
                )
              ),
              Some(tempPerms)
            )
          ),
          separation
        )
      }
    }

  }

  def checkBlock(
      block: IR.Block,
      checks: CheckImplementation,
      perms: IR.Var,
      tempPerms: IR.Var
  ): Unit = {
    for (op <- block) op match {
      case _: IR.AllocValue | _: IR.AllocArray =>
        throw new WeaverException("Unsupported alloc")

      case alloc: IR.AllocStruct =>
        checks.trackAllocation(alloc, perms)

      case assert: IR.Assert =>
        assert.kind match {
          case IR.AssertKind.Imperative =>
            val (access, _) = validateAccess(assert.value, perms, checks)
            assert.insertBefore(access)
          case IR.AssertKind.Specification =>
            assert.insertAfter(
              validateSpec(assert.value, perms, tempPerms, checks)
            )
        }

      case assign: IR.Assign => {
        val (access, _) = validateAccess(assign.value, perms, checks)
        assign.insertBefore(access)
      }

      case assign: IR.AssignMember =>
        assign.member match {
          case field: IR.FieldMember =>
            val (valueAccess, valueFields) =
              validateAccess(assign.value, perms, checks)
            val (rootAccess, rootFields) = validateAccess(
              assign.member.root,
              perms,
              checks,
              fieldAccs = valueFields
            )
            assign.insertBefore(
              valueAccess ++
                rootAccess ++
                checks.translateFieldPermission(
                  VerifyMode,
                  field,
                  perms,
                  ValueContext
                )
            )
          case _: IR.DereferenceMember | _: IR.ArrayMember =>
            throw new WeaverException("Invalid member")
        }

      case err: IR.Error => {
        val (access, _) = validateAccess(err.value, perms, checks)
        err.insertBefore(access)
      }

      case iff: IR.If =>
        val (condAccess, _) = validateAccess(iff.condition, perms, checks)
        iff.insertBefore(condAccess)
        checkBlock(iff.ifTrue, checks, perms, tempPerms)
        checkBlock(iff.ifFalse, checks, perms, tempPerms)

      case ret: IR.Return =>
        val context = ret.value match {
          case None        => ValueContext
          case Some(value) => new ReturnContext(value)
        }

        val valueAccess =
          ret.value.toSeq.flatMap(validateAccess(_, perms, checks) match {
            case (ops, _) => ops
          })

        ret.insertBefore(
          valueAccess ++
            block.method.postcondition.toSeq.flatMap(
              validateSpec(_, perms, tempPerms, checks, context = context)
            )
        )

      case loop: IR.While =>
        loop.insertBefore(
          validateAccess(loop.condition, perms, checks)._1 ++
            validateSpec(loop.invariant, perms, tempPerms, checks)
        )

        checkBlock(loop.body, checks, perms, tempPerms)

        loop.body ++= (validateAccess(loop.condition, perms, checks)._1 ++
          validateSpec(loop.invariant, perms, tempPerms, checks))

      case invoke: IR.Invoke =>
        // Pre-conditions are handled inside callee
        var fields: List[IR.Member] = Nil
        val argAccess = invoke.arguments.flatMap(arg => {
          val (argOps, argFields) =
            validateAccess(arg, perms, checks, fieldAccs = fields)
          fields = argFields
          argOps
        })
        val targetAccess = invoke.target.toSeq.flatMap(t =>
          validateAccess(t, perms, checks, fieldAccs = fields)._1
        )
        invoke.insertBefore(argAccess ++ targetAccess)

        invoke.arguments = invoke.arguments :+ perms

      case fold: IR.Fold     =>
      case unfold: IR.Unfold =>
    }
  }
}
