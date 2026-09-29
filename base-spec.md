# Product Engineer: Tech Challenge
Pharmacy Ordering System
Objective
Build a prescription fulfillment system for a pharmacy. Patients arrive at the pharmacy with a
prescription provided by their doctor. The backend system receives the prescription order and
verifies the medication inventory. The request is then routed to a pharmacist queue for approval.
Once approved, it routes to a packaging queue for final fulfillment. When it is fulfilled, a
pharmacist receives it and calls out the patient’s number for delivery. This is the
prescription-to-patient cycle.
The pharmacy's current stock consists of the following items:
● Amoxicillin (Antibiotic)
● Ibuprofen (Pain Relief)
● Lisinopril (Blood Pressure)
● Metformin (Diabetes)
● Atorvastatin (Cholesterol)
● and any of your favorite medications you'd like to add.
Focus on the experience of the pharmacy patients! They are the most important user group and
need to be convinced. They are sitting in the waiting room and need a synchronous way to track
their prescription's status until their name is called. The pharmacists and packagers are not as
important and can live with an inconvenient interface.
If you are missing details in the description of the fulfillment system, you are free to make
assumptions that allow you to keep moving forward and present a convincing solution. You are
also free to add other processes, but you need to fully cover the above prescription-to-patient
cycle for this challenge.
Context
You must use, at least, Kotlin and RabbitMQ, which are core elements of our tech stack.
Beyond that, you are free to set up your environment and pick technologies as you see fit.
Please document your system design and technology decisions.
Estimated time: approximately two to five hours. Please do not spend significantly longer, we
would rather see clean, working code than extra features. If you run out of time, submit what
you have and note what you would have done next.
Use of AI
You are welcome (and we actually expect you) to use AI coding tools to assist you. However,
during the t`echnical interview you will be expected to walk through your solution in detail and
explain the decisions you made. Please be sure that you "own" generated code, can explain it
and run it in production.`
What We Are Assessing
● User Experience: We don't expect perfect design, but we do look for simple interactions
for the pharmacy patients.
● Simplicity: No over-engineering; straightforward, readable code.
● System Design: What components you chose to build, how they interact, how the
backend processes coordinate work.
● Failure Handling: How your system deals with partial failures, where and how they
surface.
Deliverables
Please share a link to a public Git repository (GitHub, GitLab, or similar) containing your solution
and clear instructions on how to run it.