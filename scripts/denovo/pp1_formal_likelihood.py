"""Exact conditional genotype likelihood, not a theta-linkage LOD calculator.

No source qualification or evidence strength is manufactured here. Founders
and the designated proband are conditioned; missing internal genotypes are
jointly marginalized. Fraction arithmetic keeps tiny probabilities and exact
calibration boundaries independent of floating-point underflow.
"""
from decimal import Decimal, InvalidOperation
from fractions import Fraction
from itertools import product

VERSION = 'pp1-conditional-likelihood-v1'
MAX_MEMBERS = 32


class ModelError(ValueError):
    pass


def check(value, code):
    if not value:
        raise ModelError('PP1_FORMAL_' + code)


def probability(value):
    check(type(value) is str and 0 < len(value) <= 64, 'PHENOTYPE_MODEL_INVALID')
    try:
        d = Decimal(value)
        check(d.is_finite() and 0 <= d <= 1 and len(d.as_tuple().digits) <= 24
              and abs(d.as_tuple().exponent) <= 500, 'PHENOTYPE_MODEL_INVALID')
        return Fraction(d)
    except InvalidOperation as invalid:
        raise ModelError('PP1_FORMAL_PHENOTYPE_MODEL_INVALID') from invalid


def rational(value):
    # Decimal(int) is exact and avoids the process-global int->str digit limit.
    # Input sizes and factor operations remain bounded; no interpreter limit
    # is disabled for a worker or for unrelated source parsing.
    return {'numerator': format(Decimal(value.numerator), 'f'),
            'denominator': format(Decimal(value.denominator), 'f')}


def transmission(child, parent1, parent2):
    a, b = Fraction(parent1, 2), Fraction(parent2, 2)
    return ((1-a)*(1-b), a*(1-b)+(1-a)*b, a*b)[child]


class Budget:
    def __init__(self, operations, cells):
        self.operations = 0
        self.maximum = operations
        self.cells = cells

    def spend(self, amount):
        self.operations += amount
        check(self.operations <= self.maximum, 'COMPUTATION_LIMIT')


def factor(scope, function):
    values = {}
    for states in product(range(3), repeat=len(scope)):
        value = function(dict(zip(scope, states)))
        if value:
            values[states] = value
    return (tuple(scope), values)


def restrict(item, evidence):
    scope, values = item
    indices = [i for i, member in enumerate(scope) if member not in evidence]
    remaining = tuple(scope[i] for i in indices)
    out = {}
    for states, value in values.items():
        if all(states[i] == evidence[member] for i, member in enumerate(scope) if member in evidence):
            out[tuple(states[i] for i in indices)] = value
    return remaining, out


def partition(factors, evidence, budget):
    """Deterministic variable elimination; loops change width, never counting."""
    factors = [restrict(f, evidence) for f in factors]
    check(all(len(values) <= budget.cells for _, values in factors), 'COMPUTATION_LIMIT')
    while True:
        variables = set().union(*(set(f[0]) for f in factors))
        if not variables:
            total = Fraction(1)
            for _, values in factors:
                total *= values.get((), Fraction(0))
            return total
        neighborhoods = {v: set().union(*(set(s) for s, _ in factors if v in s)) for v in variables}
        variable = min(variables, key=lambda v: (len(neighborhoods[v]), v))
        selected = [f for f in factors if variable in f[0]]
        rest = [f for f in factors if variable not in f[0]]
        scope = tuple(sorted(neighborhoods[variable] - {variable}))
        check(3 ** len(scope) <= budget.cells, 'COMPUTATION_LIMIT')
        # Check before allocation/iteration, including sparse factors.
        budget.spend(3 ** (len(scope)+1) * len(selected))
        slots = {v: i for i, v in enumerate(scope)}
        lookups = [(tuple(-1 if v == variable else slots[v] for v in s), values) for s, values in selected]
        result = {}
        for states in product(range(3), repeat=len(scope)):
            summed = Fraction(0)
            for state in range(3):
                term = Fraction(1)
                for mapping, values in lookups:
                    term *= values.get(tuple(state if i == -1 else states[i] for i in mapping), Fraction(0))
                    if not term:
                        break
                summed += term
            if summed:
                result[states] = summed
        factors = rest + [(scope, result)]


def likelihood(members, observed, phenotypes, penetrances, proband, *,
               max_operations=1_000_000, max_factor_cells=32768):
    out = {'schemaVersion': VERSION, 'status': 'UNRESOLVED', 'operationCount': 0,
           'definition': 'P(G_other_observed|Y,typed_founders,G_proband)/P(G_other_observed|typed_founders,G_proband)',
           'comparison': 'CONDITIONAL_GENOTYPE_LIKELIHOOD_NOT_THETA_LINKAGE',
           'founderEvidenceConditionedAway': True}
    budget = Budget(max_operations, max_factor_cells)
    try:
        check(type(max_operations) is int and 0 < max_operations <= 1_000_000
              and type(max_factor_cells) is int and 0 < max_factor_cells <= 32768, 'COMPUTATION_LIMIT')
        check(type(members) is dict and 0 < len(members) <= MAX_MEMBERS
              and all(type(k) is str and 0 < len(k) <= 128 for k in members), 'PEDIGREE_INVALID')
        check(type(observed) is dict and type(phenotypes) is dict and type(penetrances) is dict
              and set(observed) <= set(members) and set(phenotypes) <= set(members)
              and set(penetrances) <= set(members), 'PEDIGREE_INVALID')
        check(all(type(g) is int and g in (0, 1, 2) for g in observed.values()), 'GENOTYPE_INVALID')
        for member, parents in members.items():
            check(type(parents) in (tuple, list) and len(parents) in (0, 2)
                  and all(type(p) is str for p in parents)
                  and len(set(parents)) == len(parents) and set(parents) <= set(members)
                  and member not in parents, 'PEDIGREE_INVALID')
        active, done = set(), set()

        def visit(member):
            check(member not in active, 'PEDIGREE_INVALID')
            if member in done:
                return
            active.add(member)
            for parent in members[member]:
                visit(parent)
            active.remove(member)
            done.add(member)

        for member in members:
            visit(member)
        founders = {m for m, parents in members.items() if not parents}
        check(founders <= set(observed), 'FOUNDER_GENOTYPE_REQUIRED')
        check(type(proband) is str and proband in observed, 'PROBAND_REQUIRED')
        check(all(type(v) is str and v in {'AFFECTED', 'UNAFFECTED'} for v in phenotypes.values()), 'PHENOTYPE_MODEL_INVALID')
        parsed = {}
        for member in phenotypes:
            p = penetrances.get(member)
            check(type(p) in (tuple, list) and len(p) == 3, 'PHENOTYPE_MODEL_INVALID')
            parsed[member] = tuple(probability(v) for v in p)
        anchor = {m: observed[m] for m in founders | {proband}}
        genetic = []
        for child in sorted(members):
            parents = members[child]
            if parents:
                a, b = sorted(parents)
                genetic.append(factor((a, b, child), lambda g, a=a, b=b, c=child: transmission(g[c], g[a], g[b])))
        # Founders are always conditioned, hence no frequency prior is needed.
        phenotype = []
        for member in sorted(phenotypes):
            p = parsed[member]
            affected = phenotypes[member] == 'AFFECTED'
            phenotype.append(factor((member,), lambda g, m=member, p=p, a=affected: p[g[m]] if a else 1-p[g[m]]))
        # Explicit unary units also preserve any completely isolated founder.
        genetic += [factor((m,), lambda g: Fraction(1)) for m in sorted(founders)]
        ga = partition(genetic, anchor, budget)
        go = partition(genetic, observed, budget)
        check(ga > 0 and go > 0, 'MENDELIAN_CONFLICT')
        ya = partition(genetic + phenotype, anchor, budget)
        check(ya > 0, 'ASCERTAINMENT_MODEL_CONFLICT')
        yo = partition(genetic + phenotype, observed, budget)
        bf = yo * ga / (ya * go)
        out.update(status='COMPUTED', reasonCode='PP1_FORMAL_LIKELIHOOD_COMPUTED',
                   bayesFactor=rational(bf),
                   direction='SUPPORTS' if bf > 1 else 'OPPOSES' if bf < 1 else 'UNINFORMATIVE',
                   conditionedMemberKeys=sorted(anchor),
                   observedMemberKeys=sorted(observed), phenotypeMemberKeys=sorted(phenotypes),
                   fourSums={'genotypeAnchor': rational(ga), 'genotypeAll': rational(go),
                             'phenotypeAnchor': rational(ya), 'phenotypeAll': rational(yo)})
    except ModelError as invalid:
        out['reasonCode'] = str(invalid)
    out['operationCount'] = budget.operations
    return out
