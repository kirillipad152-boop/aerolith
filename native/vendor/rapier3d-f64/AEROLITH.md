Rapier 0.26.1 (Apache-2.0, see LICENSE) with changes for Aerolith, each marked AEROLITH in the source:

- contact constraints are solved to a tolerance, with conjugate gradients over the Gauss-Seidel sweeps (`Accelerator`);
- a contact that carries much more than its own effective mass gets stiffer, with `contact_sag` as the target for static load;
- warmstart impulses are kept as forces, and those of friction in world space, so that neither a change of the step nor of
  the tangent basis kicks resting bodies;
- every contact point has its own friction coefficient (the original used that of the last point for all four);
- for many modified colliders, the broad phase calculates the same predictive AABBs with read-only parallel workers,
  then updates the sweep-and-prune proxies serially in their original order.
