import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Compute } from './compute';

describe('Compute', () => {
  let component: Compute;
  let fixture: ComponentFixture<Compute>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [Compute],
    }).compileComponents();

    fixture = TestBed.createComponent(Compute);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
